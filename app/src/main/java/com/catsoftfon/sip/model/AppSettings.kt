package com.catsoftfon.sip.model

enum class SipTransport { UDP, TCP, TLS }

/**
 * Everything the user configures - account, audio routing default, and PBX directory sync.
 * Mirrors the union of Services/SipService.cs's SipSettings and the Windows app's
 * AudioSettingsWindow / directory-sync settings, kept in one object here for simplicity.
 */
data class AppSettings(
    // Account
    var sipServer: String = "",
    var sipPort: Int = 5060,
    var transport: SipTransport = SipTransport.UDP,
    var username: String = "",
    var password: String = "",
    var displayName: String = "",
    var outboundProxy: String = "",

    // Audio
    var preferBluetooth: Boolean = true,
    var preferSpeakerForVideolessCalls: Boolean = false,

    // PBX directory sync - mirrors Services/DirectoryImportService.cs: a CSV or vCard URL
    // polled periodically and merged into the local contact list as ContactSource.DIRECTORY.
    var directorySyncUrl: String = "",
    var directorySyncEnabled: Boolean = false,
    var directorySyncIntervalMinutes: Int = 60,

    // Misc
    var startOnBoot: Boolean = false,
    var firstRunCompleted: Boolean = false,

    // ARI (Asterisk REST Interface) - optional, used only to tell a genuinely unregistered/
    // powered-off extension (grey) apart from one that's simply idle (green). The SIP "dialog"
    // event-package subscription (PresenceManager) can never make that distinction on its own -
    // see that class's doc comment - because Asterisk's dialog-info translation reports both
    // cases identically. ARI's /ari/endpoints instead reports each PJSIP extension's actual
    // registration state (online/offline) directly, with none of that ambiguity.
    //
    // Host reuses sipServer (same PBX) - only port/credentials are separate, because ARI is
    // Asterisk's own mini-HTTPS server, a completely different listener from the SIP port.
    // Leaving ariEnabled false (the default) disables this entirely; green/red still work from
    // the dialog subscription alone, only the "truly offline" grey distinction is unavailable.
    var ariEnabled: Boolean = false,
    var ariPort: Int = 8089,
    var ariUsername: String = "",
    var ariPassword: String = "",

    // Do Not Disturb / night mode - a call arriving on this line while either is active is
    // declined automatically (SIP 486 Busy, Reason.Busy - see SipManager.shouldAutoReject) with
    // no ringing, no notification, no IncomingCallActivity popping up; it still shows up in
    // История as a missed call, same as any other call nobody answered. Night mode's window is
    // minutes-since-midnight and can wrap past midnight (e.g. start=23:00/1380, end=07:00/420
    // means "declined from 23:00 to 07:00").
    var doNotDisturbEnabled: Boolean = false,
    var nightModeEnabled: Boolean = false,
    var nightModeStartMinutes: Int = 23 * 60,
    var nightModeEndMinutes: Int = 7 * 60
)
