const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const ts = () => new Date(Date.now() - 1000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const PARENT_UID = "parent_uid_1";
const CHILD_UID = "child_uid_1";
const OTHER_PARENT_UID = "other_parent_uid_2";
const FAMILY_ID = "family_alpha_1";
const OTHER_FAMILY_ID = "family_beta_2";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedFamilyWithParentAndChild() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("families").doc(FAMILY_ID).set({
      name: "Smith Family",
      ownerUid: PARENT_UID,
      createdAt: ts(),
      updatedAt: ts(),
    });
    await db
      .collection("families")
      .doc(FAMILY_ID)
      .collection("members")
      .doc(PARENT_UID)
      .set({
        uid: PARENT_UID,
        familyId: FAMILY_ID,
        role: "parent",
        displayName: "Mom",
        pairedAt: ts(),
        updatedAt: ts(),
      });
    await db
      .collection("families")
      .doc(FAMILY_ID)
      .collection("members")
      .doc(CHILD_UID)
      .set({
        uid: CHILD_UID,
        familyId: FAMILY_ID,
        role: "child",
        displayName: "Leo Pixel",
        pairedAt: ts(),
        updatedAt: ts(),
      });
    await db.collection("families").doc(OTHER_FAMILY_ID).set({
      name: "Other Family",
      ownerUid: OTHER_PARENT_UID,
      createdAt: ts(),
      updatedAt: ts(),
    });
    await db
      .collection("families")
      .doc(OTHER_FAMILY_ID)
      .collection("members")
      .doc(OTHER_PARENT_UID)
      .set({
        uid: OTHER_PARENT_UID,
        familyId: OTHER_FAMILY_ID,
        role: "parent",
        displayName: "Other Parent",
        pairedAt: ts(),
        updatedAt: ts(),
      });
  });
}

function validNotifPayload(overrides = {}) {
  return {
    sbnKey: "0|com.whatsapp|1001|null|10234",
    familyId: FAMILY_ID,
    childUid: CHILD_UID,
    packageName: "com.whatsapp",
    appLabel: "WhatsApp",
    title: "Sam",
    text: "See you at 4pm",
    bigText: "See you at 4pm",
    isMessagingStyle: true,
    conversationTitle: "After School",
    messages: ["Sam: See you at 4pm"],
    postTimeMs: 1731700000000,
    dayKey: "2026-11-16",
    updateCount: 1,
    createdAt: ts(),
    updatedAt: ts(),
    ...overrides,
  };
}

test("Unauthenticated client cannot read or write notifications", async () => {
  await seedFamilyWithParentAndChild();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("families").doc(FAMILY_ID).collection("notifications").get()
  );
  await assertFails(
    unauthDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("notifications")
      .doc("rec_1")
      .set(validNotifPayload())
  );
});

test("Child can create and update notification in own family", async () => {
  await seedFamilyWithParentAndChild();
  const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
  const docRef = childDb
    .collection("families")
    .doc(FAMILY_ID)
    .collection("notifications")
    .doc("rec_hash_1");

  await assertSucceeds(docRef.set(validNotifPayload()));
  await assertSucceeds(
    docRef.update({
      text: "Updated message text",
      updateCount: 2,
      updatedAt: ts(),
    })
  );
});

test("Child CANNOT read family notifications (parent read-only feed)", async () => {
  await seedFamilyWithParentAndChild();
  const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
  await assertFails(
    childDb.collection("families").doc(FAMILY_ID).collection("notifications").get()
  );
});

test("Parent CAN read family notifications, CANNOT write notifications", async () => {
  await seedFamilyWithParentAndChild();
  const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
  await assertSucceeds(
    childDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("notifications")
      .doc("rec_hash_1")
      .set(validNotifPayload())
  );

  const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
  await assertSucceeds(
    parentDb.collection("families").doc(FAMILY_ID).collection("notifications").get()
  );
  await assertFails(
    parentDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("notifications")
      .doc("rec_parent_spoof")
      .set(validNotifPayload({ childUid: PARENT_UID }))
  );
});

test("Cross-family isolation: other parent cannot read another family's notifications", async () => {
  await seedFamilyWithParentAndChild();
  const otherParentDb = testEnv.authenticatedContext(OTHER_PARENT_UID).firestore();
  await assertFails(
    otherParentDb.collection("families").doc(FAMILY_ID).collection("notifications").get()
  );
  await assertFails(
    otherParentDb.collection("families").doc(FAMILY_ID).get()
  );
});

test("Child cannot self-register directly into members subcollection (Cloud Function only)", async () => {
  await seedFamilyWithParentAndChild();
  const rogueDb = testEnv.authenticatedContext("rogue_uid").firestore();
  await assertFails(
    rogueDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("members")
      .doc("rogue_uid")
      .set({
        uid: "rogue_uid",
        familyId: FAMILY_ID,
        role: "child",
        displayName: "Rogue",
        pairedAt: ts(),
        updatedAt: ts(),
      })
  );
});

test("Shadow update with ghost field or role escalation is rejected", async () => {
  await seedFamilyWithParentAndChild();
  const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
  await assertFails(
    childDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("members")
      .doc(CHILD_UID)
      .update({
        role: "parent",
        updatedAt: ts(),
      })
  );
  await assertFails(
    childDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("notifications")
      .doc("rec_shadow")
      .set(validNotifPayload({ ghostField: true }))
  );
});

test("Child can push listener_disabled alert and parent can acknowledge it", async () => {
  await seedFamilyWithParentAndChild();
  const childDb = testEnv.authenticatedContext(CHILD_UID).firestore();
  const alertRef = childDb
    .collection("families")
    .doc(FAMILY_ID)
    .collection("alerts")
    .doc("alert_1");

  await assertSucceeds(
    alertRef.set({
      familyId: FAMILY_ID,
      childUid: CHILD_UID,
      type: "listener_disabled",
      message: "Notification listener access was revoked by the user or OEM.",
      acknowledged: false,
      createdAt: ts(),
      updatedAt: ts(),
    })
  );

  const parentDb = testEnv.authenticatedContext(PARENT_UID).firestore();
  await assertSucceeds(
    parentDb
      .collection("families")
      .doc(FAMILY_ID)
      .collection("alerts")
      .doc("alert_1")
      .update({
        acknowledged: true,
        updatedAt: ts(),
      })
  );
});

test("Parent can create family, member, and user profile in a WriteBatch", async () => {
  const newParentUid = "new_parent_batch_1";
  const newFamId = "fam_batch_1";
  const parentDb = testEnv.authenticatedContext(newParentUid).firestore();
  const batch = parentDb.batch();
  batch.set(parentDb.collection("families").doc(newFamId), {
    name: "Batch Family",
    ownerUid: newParentUid,
    createdAt: ts(),
    updatedAt: ts(),
  });
  batch.set(
    parentDb
      .collection("families")
      .doc(newFamId)
      .collection("members")
      .doc(newParentUid),
    {
      uid: newParentUid,
      familyId: newFamId,
      role: "parent",
      displayName: "Batch Parent",
      deviceModel: "Pixel 8",
      pairedAt: ts(),
      updatedAt: ts(),
    }
  );
  batch.set(
    parentDb.collection("users").doc(newParentUid),
    {
      userId: newParentUid,
      role: "parent",
      familyId: newFamId,
      deviceModel: "Pixel 8",
      createdAt: ts(),
      updatedAt: ts(),
    },
    { merge: true }
  );
  await assertSucceeds(batch.commit());
});

