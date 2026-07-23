package com.jarvis.os.core.context

import com.jarvis.os.core.context.model.*
import com.jarvis.os.core.context.rule.ContextAction
import com.jarvis.os.core.context.rule.RuleEngine
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying RuleEngine evaluation and proactive action generation.
 */
class RuleEngineTest {

    private val ruleEngine = RuleEngine()

    @Test
    fun whenLocationIsCollege_collegeStudyRuleTriggersSilentMode() {
        val state = ContextState(
            location = LocationSnapshot(semanticLocation = SemanticLocation.COLLEGE)
        )

        val actions = ruleEngine.evaluate(state)

        assertTrue("Expected actions for College location", actions.isNotEmpty())
        val silentAction = actions.filterIsInstance<ContextAction.SetVolumeMode>().firstOrNull()
        assertNotNull("Expected SetVolumeMode action", silentAction)
        assertTrue("Expected silent=true", silentAction!!.silent)
    }

    @Test
    fun whenMotionIsDriving_drivingSafetyRuleTriggersAutoReplyAndMaps() {
        val state = ContextState(
            motion = MotionSnapshot(type = MotionType.DRIVING, confidence = 95)
        )

        val actions = ruleEngine.evaluate(state)

        assertTrue("Expected actions for Driving motion", actions.isNotEmpty())
        val readAloudAction = actions.filterIsInstance<ContextAction.ReadNotificationsAloud>().firstOrNull()
        assertNotNull("Expected ReadNotificationsAloud action", readAloudAction)
        assertTrue(readAloudAction!!.enabled)

        val autoReplyAction = actions.filterIsInstance<ContextAction.EnableAutoReply>().firstOrNull()
        assertNotNull("Expected EnableAutoReply action", autoReplyAction)

        val mapsAction = actions.filterIsInstance<ContextAction.LaunchAppSuggestion>().firstOrNull()
        assertNotNull("Expected Maps suggestion", mapsAction)
        assertEquals("com.google.android.apps.maps", mapsAction!!.packageName)
    }

    @Test
    fun whenBatteryBelow15Percent_lowBatteryRuleTriggersThrottling() {
        val state = ContextState(
            battery = BatterySnapshot(levelPercentage = 10, isCharging = false)
        )

        val actions = ruleEngine.evaluate(state)

        val saverAction = actions.filterIsInstance<ContextAction.SuggestBatterySaver>().firstOrNull()
        assertNotNull("Expected SuggestBatterySaver action", saverAction)
        assertTrue(saverAction!!.enable)
    }
}
