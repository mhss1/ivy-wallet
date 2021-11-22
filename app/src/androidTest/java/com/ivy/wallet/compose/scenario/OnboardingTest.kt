package com.ivy.wallet.compose.scenario

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import com.ivy.wallet.compose.IvyComposeTest
import com.ivy.wallet.compose.helpers.MainBottomBar
import com.ivy.wallet.compose.helpers.OnboardingFlow
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

@HiltAndroidTest
class OnboardingTest : IvyComposeTest() {

    @Test
    fun contextLoads() {
    }

    @Test
    fun OnboardingShortestPath() {
        val onboarding = OnboardingFlow(composeTestRule)
        val mainBottomBar = MainBottomBar(composeTestRule)

        onboarding.chooseOfflineAccount()
        onboarding.clickStartFresh()
        onboarding.setCurrency()
        onboarding.skipAccounts()
        onboarding.skipCategories()

        mainBottomBar.clickAccounts()

        composeTestRule.onNode(hasText("Cash"))
            .assertIsDisplayed()
    }
}