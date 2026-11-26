// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.theme

enum class UiLanguage {
    ENGLISH, HINGLISH
}

data class YSCodeStrings(
    val runAction: String,
    val stopAction: String,
    val viewDiff: String,
    val accept: String,
    val reject: String,
    val deny: String,
    val allowOnce: String,
    val allowSession: String,
    val alwaysAllow: String,
    val retry: String,
    val messageHint: String,
    val offlineBanner: String,
    val noProjectsTitle: String,
    val noProjectsSubtitle: String,
    val newProject: String
)

fun stringsFor(language: UiLanguage): YSCodeStrings = when (language) {
    UiLanguage.ENGLISH -> YSCodeStrings(
        runAction = "Run",
        stopAction = "Stop",
        viewDiff = "View diff",
        accept = "Accept",
        reject = "Reject",
        deny = "Deny",
        allowOnce = "Allow once",
        allowSession = "Allow for session",
        alwaysAllow = "Always allow",
        retry = "Retry",
        messageHint = "Message YSCode…  ( /  @  ! )",
        offlineBanner = "Offline — Linux & files work, cloud LLM needs internet (or LAN Ollama/LM Studio)",
        noProjectsTitle = "No projects yet",
        noProjectsSubtitle = "Create a workspace, clone a repository, or import a folder to start coding.",
        newProject = "New Project"
    )
    UiLanguage.HINGLISH -> YSCodeStrings(
        runAction = "Chalao",
        stopAction = "Rok do",
        viewDiff = "Badlav dekho",
        accept = "Manzoor",
        reject = "Wapas lo",
        deny = "Mana karo",
        allowOnce = "Ek baar allow",
        allowSession = "Is session ke liye",
        alwaysAllow = "Hamesha allow",
        retry = "Dobara koshish",
        messageHint = "YSCode ko likho…  ( /  @  ! )",
        offlineBanner = "Offline — Linux aur files chalenge, cloud LLM ko net chahiye (ya local Ollama)",
        noProjectsTitle = "Koi project nahi hai",
        noProjectsSubtitle = "Naya workspace banao, git clone karo, ya folder import karke shuru karo.",
        newProject = "Naya Project"
    )
}
