import * as crypto from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onDocumentCreated } from "firebase-functions/v2/firestore";

initializeApp();

const DATABASE_ID =
  process.env.FIRESTORE_DATABASE_ID ||
  "ai-studio-android-kinecho-1eeff52d-e830-4e35-afb4-325607b9d270";

const db = getFirestore(DATABASE_ID);
const messaging = getMessaging();

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_LENGTH = 6;
const CODE_TTL_MS = 15 * 60 * 1000;

function generateSixCharCode(): string {
  const bytes = crypto.randomBytes(CODE_LENGTH);
  let out = "";
  for (let i = 0; i < CODE_LENGTH; i++) {
    out += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
  }
  return out;
}

/**
 * Parent calls this function to mint a single-use 6-character pairing code
 * bound to their familyId.
 */
export const generatePairingCode = onCall(async (request) => {
  const uid = request.auth?.uid;
  if (!uid) {
    throw new HttpsError("unauthenticated", "Google Sign-In required.");
  }

  const familyId = String(request.data?.familyId || "").trim();
  if (!/^[a-zA-Z0-9_\-]{1,128}$/.test(familyId)) {
    throw new HttpsError("invalid-argument", "Invalid familyId.");
  }

  const memberRef = db
    .collection("families")
    .doc(familyId)
    .collection("members")
    .doc(uid);
  const memberSnap = await memberRef.get();
  if (!memberSnap.exists || memberSnap.get("role") !== "parent") {
    throw new HttpsError(
      "permission-denied",
      "Only a parent member of this family may generate pairing codes."
    );
  }

  const expiresAtMs = Date.now() + CODE_TTL_MS;
  const expiresAt = Timestamp.fromMillis(expiresAtMs);

  // Retry on rare 6-char collision
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = generateSixCharCode();
    const codeRef = db.collection("pairing_codes").doc(code);
    try {
      await codeRef.create({
        code,
        familyId,
        createdByUid: uid,
        used: false,
        expiresAt,
        createdAt: FieldValue.serverTimestamp(),
      });
      return { code, expiresAtMs };
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      if (!msg.includes("ALREADY_EXISTS")) {
        throw err;
      }
    }
  }

  throw new HttpsError("resource-exhausted", "Could not allocate unique pairing code.");
});

/**
 * Child calls this function with the 6-character code entered by the parent.
 * Validates expiry + single-use inside a transaction and writes the familyId
 * child membership binding.
 */
export const redeemPairingCode = onCall(async (request) => {
  const childUid = request.auth?.uid;
  if (!childUid) {
    throw new HttpsError("unauthenticated", "Google Sign-In required.");
  }

  const rawCode = String(request.data?.code || "")
    .trim()
    .toUpperCase();
  if (!/^[A-Z0-9]{6}$/.test(rawCode)) {
    throw new HttpsError("invalid-argument", "Pairing code must be 6 characters.");
  }

  const displayName = String(request.data?.displayName || "Child Device")
    .trim()
    .slice(0, 80);
  const deviceModel = String(request.data?.deviceModel || "Android")
    .trim()
    .slice(0, 128);
  const fcmToken = String(request.data?.fcmToken || "")
    .trim()
    .slice(0, 512);

  const codeRef = db.collection("pairing_codes").doc(rawCode);

  const result = await db.runTransaction(async (tx) => {
    const codeSnap = await tx.get(codeRef);
    if (!codeSnap.exists) {
      throw new HttpsError("not-found", "Pairing code not found.");
    }

    const data = codeSnap.data()!;
    if (data.used === true) {
      throw new HttpsError("failed-precondition", "Pairing code has already been used.");
    }

    const expiresAt = data.expiresAt as Timestamp | undefined;
    if (!expiresAt || expiresAt.toMillis() < Date.now()) {
      throw new HttpsError("deadline-exceeded", "Pairing code has expired.");
    }

    const familyId = String(data.familyId);
    const familyRef = db.collection("families").doc(familyId);
    const familySnap = await tx.get(familyRef);
    if (!familySnap.exists) {
      throw new HttpsError("not-found", "Linked family no longer exists.");
    }

    const memberRef = familyRef.collection("members").doc(childUid);
    const userRef = db.collection("users").doc(childUid);

    tx.update(codeRef, {
      used: true,
      usedByUid: childUid,
      usedAt: FieldValue.serverTimestamp(),
    });

    const memberPayload: Record<string, unknown> = {
      uid: childUid,
      familyId,
      role: "child",
      displayName: displayName || "Child Device",
      deviceModel,
      listenerActive: false,
      companionTrusted: false,
      pairedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (fcmToken) {
      memberPayload.fcmToken = fcmToken;
    }
    tx.set(memberRef, memberPayload);

    const userPayload: Record<string, unknown> = {
      userId: childUid,
      role: "child",
      familyId,
      deviceModel,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    };
    if (fcmToken) {
      userPayload.fcmToken = fcmToken;
    }
    tx.set(userRef, userPayload, { merge: true });

    return {
      familyId,
      familyName: String(familySnap.get("name") || "Family"),
    };
  });

  return result;
});

async function sendParentDataPush(
  familyId: string,
  dataPayload: { type: string; recordId: string; familyId: string },
  highPriority: boolean
): Promise<void> {
  const parentsSnap = await db
    .collection("families")
    .doc(familyId)
    .collection("members")
    .where("role", "==", "parent")
    .get();

  const tokens = parentsSnap.docs
    .map((d) => String(d.get("fcmToken") || "").trim())
    .filter((t) => t.length > 0);

  if (tokens.length === 0) return;

  // Requirement 6: FCM data messages carry ONLY lightweight identifiers ({type, recordId}),
  // keeping payloads well under FCM's 4 KB ceiling and avoiding PII leakage in push transport.
  await messaging.sendEachForMulticast({
    tokens,
    data: dataPayload,
    android: {
      priority: highPriority ? "high" : "normal",
    },
  });
}

export const onNotificationCreated = onDocumentCreated(
  {
    database: DATABASE_ID,
    document: "families/{familyId}/notifications/{recordId}",
  },
  async (event) => {
    const { familyId, recordId } = event.params;
    await sendParentDataPush(
      familyId,
      {
        type: "new_notification",
        recordId,
        familyId,
      },
      false
    );
  }
);

export const onListenerAlertCreated = onDocumentCreated(
  {
    database: DATABASE_ID,
    document: "families/{familyId}/alerts/{alertId}",
  },
  async (event) => {
    const { familyId, alertId } = event.params;
    await sendParentDataPush(
      familyId,
      {
        type: "listener_alert",
        recordId: alertId,
        familyId,
      },
      true
    );
  }
);
