package dev.gamecontrols.link

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ManifestPolicyTest {
    private val namespace = "http://schemas.android.com/apk/res/android"
    private val manifest get() = parse(File(requireNotNull(System.getProperty("android.manifest"))))
    private fun resource(path: String) = parse(File(requireNotNull(System.getProperty("android.resources")), path))
    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(file)
    private fun Element.android(name: String) = getAttributeNS(namespace, name)

    @Test fun permissionsExcludeAudioCameraRootOverlayAndAutomaticPermissionControl() {
        val nodes = manifest.getElementsByTagName("uses-permission")
        val permissions = (0 until nodes.length).map { (nodes.item(it) as Element).android("name") }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
            "android.permission.POST_NOTIFICATIONS"), permissions)
    }

    @Test fun backupCleartextAndUnprotectedServiceEntrypointsAreDisabled() {
        val document = manifest
        val application = document.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.android("allowBackup"))
        assertEquals("false", application.android("fullBackupContent"))
        assertEquals("false", application.android("usesCleartextTraffic"))
        val services = document.getElementsByTagName("service")
        assertEquals(2, services.length)
        for (index in 0 until services.length) {
            val service = services.item(index) as Element
            if (service.android("name").endsWith("CaptureService")) {
                assertEquals("false", service.android("exported"))
                assertEquals("mediaProjection", service.android("foregroundServiceType"))
            } else assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.android("permission"))
        }
    }

    @Test fun onlyLauncherActivityIsExternallyReachableAndNoPairingDeepLinkAutoStarts() {
        val nodes = manifest.getElementsByTagName("activity")
        val exported = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.android("exported") == "true" }.map { it.android("name") }
        assertEquals(listOf(".ui.MainActivity"), exported)
        assertEquals(0, manifest.getElementsByTagName("data").length)
        assertEquals(0, manifest.getElementsByTagName("receiver").length)
    }

    @Test fun accessibilityCapabilityIsOnlyGesturesAndWindowSafetyMetadata() {
        val service = resource("xml/manual_accessibility.xml").documentElement
        assertEquals("true", service.android("canPerformGestures"))
        assertEquals("true", service.android("canRetrieveWindowContent"))
        assertEquals("flagRetrieveInteractiveWindows", service.android("accessibilityFlags"))
        assertEquals("typeWindowStateChanged|typeWindowsChanged", service.android("accessibilityEventTypes"))
        assertFalse(service.hasAttributeNS(namespace, "canRequestFilterKeyEvents"))
        assertFalse(service.hasAttributeNS(namespace, "canRequestTouchExplorationMode"))
    }

    @Test fun invitationFieldsAreNotSavedOrAutofilled() {
        val document = resource("layout/activity_main.xml")
        val fields = listOf("EditText", "TextView").flatMap { name ->
            val nodes = document.getElementsByTagName(name)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.filter { it.android("id") in setOf("@+id/clientInvitation", "@+id/invitation") }
        assertEquals(2, fields.size)
        fields.forEach {
            assertEquals("false", it.android("saveEnabled"))
            assertEquals("noExcludeDescendants", it.android("importantForAutofill"))
        }
    }

    @Test fun noConfigurationOverrideCanHideRotationFromActivityLifecycle() {
        val activities = manifest.getElementsByTagName("activity")
        for (index in 0 until activities.length) {
            assertFalse((activities.item(index) as Element).hasAttributeNS(namespace, "configChanges"))
        }
    }
}
