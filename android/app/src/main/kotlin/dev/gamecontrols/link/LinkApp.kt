package dev.gamecontrols.link

import android.app.Application
import dev.gamecontrols.link.control.ManualAccessibilityService
import dev.gamecontrols.link.host.HostRuntime
import dev.gamecontrols.link.protocol.Invitation

class LinkApp : Application() {
    val host by lazy { HostRuntime(this) }
    var accessibility: ManualAccessibilityService? = null
    var pendingClientInvitation: Invitation? = null
}
