package neth.iecal.curbox.hardcoded

import neth.iecal.curbox.data.models.BrowserUrlBarInfo

val URL_BAR_ID_LIST = mapOf(
    // Chromium / Blink Engine Browsers
    "app.vanadium.browser" to BrowserUrlBarInfo(
        displayUrlBarId = "app.vanadium.browser:id/url_bar",
    ),
    "com.android.chrome" to BrowserUrlBarInfo(
        displayUrlBarId = "com.android.chrome:id/url_bar",
    ),
    "com.brave.browser" to BrowserUrlBarInfo(
        displayUrlBarId = "com.brave.browser:id/url_bar",
    ),
    "com.opera.browser" to BrowserUrlBarInfo(
        displayUrlBarId = "com.opera.browser:id/url_field",
    ),
    "com.vivaldi.browser" to BrowserUrlBarInfo(
        displayUrlBarId = "com.vivaldi.browser:id/url_bar",
    ),
    "foundation.e.browser" to BrowserUrlBarInfo(
        displayUrlBarId = "foundation.e.browser:id/url_bar",
    ),
    "io.github.jqssun.helium" to BrowserUrlBarInfo(
        displayUrlBarId = "io.github.jqssun.helium:id/url_bar",
    ),
    "org.calyxos.chromium" to BrowserUrlBarInfo(
        displayUrlBarId = "org.calyxos.chromium:id/url_bar",
    ),
    "org.cromite.cromite" to BrowserUrlBarInfo(
        displayUrlBarId = "org.cromite.cromite:id/url_bar",
    ),

    // Gecko / GeckoView Engine Browsers (Firefox)
    "io.github.forkmaintainers.iceraven" to BrowserUrlBarInfo(
        displayUrlBarId = "ADDRESSBAR_URL_BOX",
    ),
    "net.waterfox.android.release" to BrowserUrlBarInfo(
        displayUrlBarId = "ADDRESSBAR_URL_BOX",
    ),
    "org.ironfoxoss.ironfox" to BrowserUrlBarInfo(
        displayUrlBarId = "ADDRESSBAR_URL_BOX",
    ),
    "org.mozilla.fennec_fdroid" to BrowserUrlBarInfo(
        displayUrlBarId = "ADDRESSBAR_URL_BOX",
    ),
    "org.mozilla.firefox" to BrowserUrlBarInfo(
        displayUrlBarId = "ADDRESSBAR_URL_BOX",
    ),
    "org.mozilla.focus" to BrowserUrlBarInfo(
        displayUrlBarId = "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
    ),
)