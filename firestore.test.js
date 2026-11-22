const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_ops_123";
const BOB_UID = "bob_ops_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function getServerTimestamp() {
  try {
    const firebase = require("firebase/compat/app");
    require("firebase/compat/firestore");
    const app = firebase.default || firebase;
    if (app && app.firestore && app.firestore.FieldValue && app.firestore.FieldValue.serverTimestamp) {
      return app.firestore.FieldValue.serverTimestamp();
    }
  } catch {
    // Fall back to modular SDK
  }
  const { serverTimestamp } = require("firebase/firestore");
  return serverTimestamp();
}

function validIncidentPayload(userId, overrides = {}) {
  const now = new Date(Date.now() - 5000);
  return {
    userId,
    title: "Duplicate charge on order #ORD-8821",
    channel: "#vip-support",
    industryPack: "ECOMMERCE",
    severity: "HIGH",
    status: "NEEDS_ACTION",
    noiseSuppressed: false,
    scrubbedMessage: "Customer [Protected Email] account [Protected Account] charged twice.",
    redactionCount: 2,
    triageSummary: "Billing duplicate charge detected locally.",
    suggestedReply: "Hi there, we spotted a duplicate authorization and are reviewing it right now.",
    inferenceSource: "ON_DEVICE_NANO",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validAlertStatePayload(orgId, alertHash, userId, overrides = {}) {
  return {
    alertHash,
    orgId,
    ownerId: userId,
    status: "open",
    bucket: "needs_action",
    updatedBy: userId,
    updatedAt: getServerTimestamp(),
    ...overrides,
  };
}

function validConnectorPayload(userId, overrides = {}) {
  const now = new Date(Date.now() - 5000);
  return {
    userId,
    name: "Acme Warehouse MCP",
    connectorType: "MCP_SERVER",
    endpointUrl: "https://mcp.example.com/sse",
    toolNames: ["inventory_check", "shipment_hold"],
    toolCount: 2,
    enabled: true,
    statusLabel: "Connected",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

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

test("Unauthenticated users cannot read or write incidents, connectors, or settings", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("incidents").get());
  await assertFails(
    unauthDb.collection("incidents").doc("inc_1").set(validIncidentPayload(ALICE_UID))
  );
  await assertFails(unauthDb.collection("connectors").get());
  await assertFails(unauthDb.collection("user_settings").doc(ALICE_UID).get());
});

test("Incidents: owner create, filtered list, cloud investigation, approve, and terminal lock", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ref = aliceDb.collection("incidents").doc("inc_101");

  // Create incident mirroring Kotlin repository payload
  await assertSucceeds(ref.set(validIncidentPayload(ALICE_UID)));

  // Filtered query matching Kotlin observeIncidents()
  await assertSucceeds(
    aliceDb.collection("incidents").where("userId", "==", ALICE_UID).get()
  );

  // Unfiltered query must fail
  await assertFails(aliceDb.collection("incidents").get());

  // Local triage update succeeds
  await assertSucceeds(
    ref.update({
      scrubbedMessage: "Customer [Protected Email] account [Protected Account] double billed.",
      redactionCount: 2,
      triageSummary: "Confirmed high-priority billing alert on device.",
      suggestedReply: "We are checking this right away.",
      inferenceSource: "ON_DEVICE_NANO",
      noiseSuppressed: false,
      severity: "HIGH",
      status: "NEEDS_ACTION",
      updatedAt: new Date(Date.now() - 2000),
    })
  );

  // Cloud investigation update succeeds
  await assertSucceeds(
    ref.update({
      diagnosis: "Payment gateway retried capture after a brief webhook timeout.",
      toolsUsed: ["order_lookup", "payment_ledger_check", "refund_processor"],
      totalToolsAvailable: 48,
      investigatedRole: "FINANCE_LEAD",
      suggestedReply: "We voided the duplicate capture and confirmed your order is shipping.",
      inferenceSource: "CLOUD_INVESTIGATION",
      status: "INVESTIGATED",
      updatedAt: new Date(Date.now() - 1500),
    })
  );

  // Approve & Reply in Slack transitions to terminal RESOLVED
  await assertSucceeds(
    ref.update({
      status: "RESOLVED",
      approvedReply: "We voided the duplicate capture and confirmed your order is shipping.",
      approvedByRole: "FINANCE_LEAD",
      slackPosted: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Terminal state lock: subsequent updates must fail
  await assertFails(
    ref.update({
      status: "NEEDS_ACTION",
      noiseSuppressed: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Incidents: reject shadow fields, @DocumentId id field, future timestamps, and cross-user access", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Reject unexpected @DocumentId `id` field
  await assertFails(
    aliceDb.collection("incidents").doc("inc_bad_id").set(
      validIncidentPayload(ALICE_UID, { id: "inc_bad_id" })
    )
  );

  // Reject ghost/shadow field
  await assertFails(
    aliceDb.collection("incidents").doc("inc_ghost").set(
      validIncidentPayload(ALICE_UID, { isAdminOverride: true })
    )
  );

  // Reject future timestamp
  await assertFails(
    aliceDb.collection("incidents").doc("inc_future").set(
      validIncidentPayload(ALICE_UID, { createdAt: new Date(Date.now() + 60000) })
    )
  );

  // Reject rawMessage PII field on create and update
  await assertFails(
    aliceDb.collection("incidents").doc("inc_raw_pii").set(
      validIncidentPayload(ALICE_UID, {
        rawMessage: "Customer jane.doe@example.com account ACCT-90210 charged twice.",
      })
    )
  );

  // Seed Alice's incident, verify Bob cannot read/update/delete, and Alice cannot inject rawMessage on update
  await assertSucceeds(
    aliceDb.collection("incidents").doc("inc_private").set(validIncidentPayload(ALICE_UID))
  );
  await assertFails(
    aliceDb.collection("incidents").doc("inc_private").update({
      rawMessage: "Injected unredacted PII",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(bobDb.collection("incidents").doc("inc_private").get());
  await assertFails(
    bobDb.collection("incidents").doc("inc_private").update({
      status: "HIDDEN_NOISE",
      noiseSuppressed: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(bobDb.collection("incidents").doc("inc_private").delete());
});

test("Connectors & UserSettings: owner CRUD succeeds, cross-user and invalid payloads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Connector create, filtered list, toggle, delete
  const connRef = aliceDb.collection("connectors").doc("conn_mcp_1");
  await assertSucceeds(connRef.set(validConnectorPayload(ALICE_UID)));
  await assertSucceeds(
    aliceDb.collection("connectors").where("userId", "==", ALICE_UID).get()
  );
  await assertFails(aliceDb.collection("connectors").get());
  await assertSucceeds(
    connRef.update({
      enabled: false,
      statusLabel: "Paused",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(bobDb.collection("connectors").doc("conn_mcp_1").get());
  await assertSucceeds(connRef.delete());

  // UserSettings create, read, update pack/role & webhook
  const settingsRef = aliceDb.collection("user_settings").doc(ALICE_UID);
  const now = new Date(Date.now() - 5000);
  await assertSucceeds(
    settingsRef.set({
      userId: ALICE_UID,
      activePack: "ECOMMERCE",
      activeRole: "SUPPORT",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(settingsRef.get());
  await assertSucceeds(
    settingsRef.update({
      activePack: "B2B_SAAS",
      activeRole: "ADMIN",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertSucceeds(
    settingsRef.update({
      slackWebhookUrl: "https://hooks.slack.com/services/T000/B000/XXXX",
      slackChannel: "#ops-alerts",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertSucceeds(
    settingsRef.update({
      orgId: "org_acme_1",
      orgName: "Acme Ops",
      activeRole: "FINANCE_LEAD",
      onboardingCompleted: true,
      updatedAt: new Date(Date.now() - 400),
    })
  );
  await assertFails(bobDb.collection("user_settings").doc(ALICE_UID).get());
  await assertFails(
    aliceDb.collection("user_settings").doc(ALICE_UID).update({
      activePack: "INVALID_PACK",
      activeRole: "ADMIN",
      updatedAt: new Date(Date.now() - 300),
    })
  );
});

test("Orgs: owner workspace create, reject Slack secrets, allow connection toggle, and cross-user denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 5000);

  // Reject creating org with client-supplied Slack secrets
  await assertFails(
    aliceDb.collection("orgs").doc("org_secret_create").set({
      userId: ALICE_UID,
      orgName: "Leaky Org",
      operatorRole: "FINANCE_LEAD",
      webhookUrl: "https://calmduty.web.app/webhooks/slack/org_secret_create",
      slackBotToken: "xoxb-never-allowed",
      slackSigningSecret: "secret-never-allowed",
      slackConnected: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Server-only Slack OAuth collection is strictly inaccessible to clients
  await assertFails(aliceDb.collection("server_slack_connections").doc("org_acme_1").get());
  await assertFails(
    aliceDb.collection("server_slack_connections").doc("org_acme_1").set({ token: "xoxb-secret" })
  );

  const orgRef = aliceDb.collection("orgs").doc("org_acme_1");
  await assertSucceeds(
    orgRef.set({
      userId: ALICE_UID,
      orgName: "Acme Operations",
      operatorRole: "FINANCE_LEAD",
      webhookUrl: "https://calmduty.web.app/webhooks/slack/org_acme_1",
      slackConnected: false,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(aliceDb.collection("orgs").where("userId", "==", ALICE_UID).get());
  await assertFails(aliceDb.collection("orgs").get());

  // Reject saving Slack bot token or signing secret on update
  await assertFails(
    orgRef.update({
      slackBotToken: "xoxb-sample-token",
      slackSigningSecret: "secret-12345",
      slackConnected: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Updating slackConnected status without secrets succeeds
  await assertSucceeds(
    orgRef.update({
      slackConnected: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject cross-user read/update and shadow fields
  await assertFails(bobDb.collection("orgs").doc("org_acme_1").get());
  await assertFails(
    bobDb.collection("orgs").doc("org_acme_1").update({
      orgName: "Hijacked",
      operatorRole: "ADMIN",
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    aliceDb.collection("orgs").doc("org_bad").set({
      userId: ALICE_UID,
      orgName: "Ghost Org",
      operatorRole: "SUPPORT",
      webhookUrl: "https://calmduty.web.app/webhooks/slack/org_bad",
      slackConnected: false,
      isSuperAdmin: true,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Zero-Knowledge Alert States: blind HMAC hash sync enforces strict schema, immutability, and org isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date(Date.now() - 5000);
  const orgId = "org_acme_zk";
  const alertHash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

  // Orphaned subcollection create under nonexistent org fails
  await assertFails(
    aliceDb
      .collection("orgs")
      .doc("org_nonexistent")
      .collection("alert_states")
      .doc(alertHash)
      .set(validAlertStatePayload("org_nonexistent", alertHash, ALICE_UID))
  );

  // Create parent org
  await assertSucceeds(
    aliceDb.collection("orgs").doc(orgId).set({
      userId: ALICE_UID,
      orgName: "Acme ZK",
      operatorRole: "SUPPORT",
      webhookUrl: `https://calmduty.web.app/webhooks/slack/${orgId}`,
      slackConnected: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  const alertRef = aliceDb
    .collection("orgs")
    .doc(orgId)
    .collection("alert_states")
    .doc(alertHash);

  // Reject PII/text/title/body/rawMessage fields on create
  for (const forbidden of [
    { rawMessage: "customer@example.com" },
    { text: "raw alert text" },
    { title: "Alert Title" },
    { body: "Alert body" },
  ]) {
    await assertFails(
      alertRef.set(validAlertStatePayload(orgId, alertHash, ALICE_UID, forbidden))
    );
  }

  // Reject oversized alertHash (> 64 chars)
  const longHash = "a".repeat(65);
  await assertFails(
    aliceDb
      .collection("orgs")
      .doc(orgId)
      .collection("alert_states")
      .doc(longHash)
      .set(validAlertStatePayload(orgId, longHash, ALICE_UID))
  );

  // Reject client-forged updatedAt timestamp (must equal server request.time)
  await assertFails(
    alertRef.set(
      validAlertStatePayload(orgId, alertHash, ALICE_UID, {
        updatedAt: new Date(Date.now() - 5000),
      })
    )
  );

  // Valid create with optional expireAt and snoozedUntil: null succeeds
  const expireAt = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
  await assertSucceeds(
    alertRef.set(
      validAlertStatePayload(orgId, alertHash, ALICE_UID, {
        expireAt,
        snoozedUntil: null,
      })
    )
  );

  // Owner get & list succeed
  await assertSucceeds(alertRef.get());
  await assertSucceeds(aliceDb.collection("orgs").doc(orgId).collection("alert_states").get());

  // Cross-user read/list/update/delete fail
  const bobAlertRef = bobDb
    .collection("orgs")
    .doc(orgId)
    .collection("alert_states")
    .doc(alertHash);
  await assertFails(bobAlertRef.get());
  await assertFails(bobDb.collection("orgs").doc(orgId).collection("alert_states").get());
  await assertFails(
    bobAlertRef.update({
      status: "resolved",
      updatedBy: BOB_UID,
      updatedAt: getServerTimestamp(),
    })
  );
  await assertFails(bobAlertRef.delete());

  // Valid update (snoozed with timestamp) succeeds
  await assertSucceeds(
    alertRef.update({
      status: "snoozed",
      bucket: "hidden_noise",
      snoozedUntil: new Date(Date.now() + 3600 * 1000),
      updatedBy: ALICE_UID,
      updatedAt: getServerTimestamp(),
    })
  );

  // Reject immutable ownerId/orgId/alertHash modification or text injection on update
  await assertFails(
    alertRef.update({
      ownerId: BOB_UID,
      updatedBy: ALICE_UID,
      updatedAt: getServerTimestamp(),
    })
  );
  await assertFails(
    alertRef.update({
      body: "Sneaky PII on update",
      updatedBy: ALICE_UID,
      updatedAt: getServerTimestamp(),
    })
  );

  // Owner delete succeeds
  await assertSucceeds(alertRef.delete());
});

