# First-release signing setup (not yet complete)

The owner confirmed this is Eroflix's first release. No previous signing key
needs to be replaced, and no release key or password has been created here.
The Android application ID is `tech.imitravanu.eroflix`; the release build is
arm64 and requires the full catalog.

The build accepts a signing identity through these environment variables:
`EROFLIX_SIGNING_STORE_FILE`, `EROFLIX_SIGNING_STORE_PASSWORD`,
`EROFLIX_SIGNING_KEY_ALIAS`, and `EROFLIX_SIGNING_KEY_PASSWORD`. All four are
required together. Keep the keystore and passwords outside source control and
back them up securely; losing the key prevents later in-place app updates.
When these variables are absent the release build remains unsigned and must not
be delivered as a signed release. Do not put passwords in chat or commit them.

The owner identified `https://github.com/imitravanu/eroflix-mobile` as the
separate mobile release repository. On 2026-10-29 its GitHub API metadata
reported it public, empty, and without releases. The update service still needs
an independently managed manifest signing key, release assets and their HTTPS
locations, a configured trusted public key, and the
P09/P10 recovery and device acceptance work. The prepared signing configuration
is not evidence of a signed or publishable app.
