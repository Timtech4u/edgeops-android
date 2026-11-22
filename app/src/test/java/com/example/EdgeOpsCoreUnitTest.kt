package com.example

import com.example.ai.LocalPrivacyShield
import com.example.data.ConnectorUtils
import com.example.data.DecisionScores
import com.example.data.Incident
import com.example.data.IncidentGrouper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EdgeOpsCoreUnitTest {

    @Test
    fun `LocalPrivacyShield scrubs emails phones cards accounts and Slack links locally`() {
        val raw =
            "Customer <mailto:maya.chen@northstar-demo.io|maya.chen@northstar-demo.io> " +
                "(phone 415-890-2341, account acct_9948210K) hit duplicate hold on card 4532-8910-4421-9004."

        val report = LocalPrivacyShield.redactLocally(raw)

        assertFalse(report.scrubbedText.contains("maya.chen@northstar-demo.io"))
        assertFalse(report.scrubbedText.contains("415-890-2341"))
        assertFalse(report.scrubbedText.contains("acct_9948210K"))
        assertFalse(report.scrubbedText.contains("4532-8910-4421-9004"))
        assertTrue(report.scrubbedText.contains("[Protected Email]"))
        assertTrue(report.scrubbedText.contains("[Protected Phone]"))
        assertTrue(report.scrubbedText.contains("[Protected Account]"))
        assertTrue(report.redactions.size >= 4)
    }

    @Test
    fun `IncidentGrouper collapses repeated alerts and separates distinct businesses`() {
        val a1 = Incident(
            id = "inc_1",
            title = "[NORTHSTAR] Compliance alert digest",
            channel = "#compliance-kyc",
            rawMessage = "Business: Acme Logistics\nOverdue jobs: 12"
        )
        val a2 = Incident(
            id = "inc_2",
            title = "[NORTHSTAR] Compliance alert digest",
            channel = "#compliance-kyc",
            rawMessage = "Business: Acme Logistics\nOverdue jobs: 14"
        )
        val b1 = Incident(
            id = "inc_3",
            title = "[NORTHSTAR] Compliance alert digest",
            channel = "#compliance-kyc",
            rawMessage = "Business: BrightShop Retail\nOverdue jobs: 3"
        )

        val keyA1 = IncidentGrouper.buildKey(a1, a1.title)
        val keyA2 = IncidentGrouper.buildKey(a2, a2.title)
        val keyB1 = IncidentGrouper.buildKey(b1, b1.title)

        assertEquals(keyA1, keyA2)
        assertNotEquals(keyA1, keyB1)
    }

    @Test
    fun `ConnectorUtils parses OpenAPI 3 spec operations cleanly`() {
        val spec = """
            {
              "openapi": "3.0.3",
              "info": { "title": "Internal Billing API", "version": "1.0.0" },
              "servers": [{ "url": "https://billing.northstar.internal/v1" }],
              "paths": {
                "/ledger/tx/{id}": {
                  "get": { "operationId": "ledger_get_tx" }
                },
                "/ledger/reconcile": {
                  "post": { "operationId": "ledger_reconcile" }
                }
              }
            }
        """.trimIndent()

        val parsed = ConnectorUtils.parseOpenApiSpec(spec)
        assertTrue(parsed.isSuccess)
        val (title, baseUrl, tools) = parsed.getOrThrow()
        assertEquals("Internal Billing API", title)
        assertEquals("https://billing.northstar.internal/v1", baseUrl)
        assertEquals(listOf("ledger_get_tx", "ledger_reconcile"), tools)
    }

    @Test
    fun `DecisionScores 6-signal routing sends auto-frozen card to notify_ops and routine top-up to ignore`() {
        val frozenCard = DecisionScores(
            customerBlocked = 0.94,
            needsAction = 0.38,
            isNoise = 0.11,
            revenueRisk = 0.34,
            manualMoneyMove = 0.02,
            needsEngineering = 0.19,
            priorityScore = 94
        )
        assertEquals("notify_ops", frozenCard.effectiveRoute())
        assertEquals("Notify Ops", frozenCard.routeShortLabel())

        val routineTopUp = DecisionScores(
            customerBlocked = 0.02,
            needsAction = 0.03,
            isNoise = 0.78,
            revenueRisk = 0.04,
            manualMoneyMove = 0.03,
            needsEngineering = 0.01,
            priorityScore = 4
        )
        assertEquals("ignore", routineTopUp.effectiveRoute())
        assertEquals("Ignored", routineTopUp.routeShortLabel())

        val backendSpike = DecisionScores(
            customerBlocked = 0.85,
            needsAction = 0.95,
            isNoise = 0.03,
            revenueRisk = 0.91,
            manualMoneyMove = 0.04,
            needsEngineering = 0.84,
            priorityScore = 95
        )
        assertEquals("page_eng", backendSpike.effectiveRoute())
        assertEquals("Page Eng", backendSpike.routeShortLabel())
    }
}
