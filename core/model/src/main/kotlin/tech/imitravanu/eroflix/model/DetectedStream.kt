package tech.imitravanu.eroflix.model

/** A media URL observed in the currently visible web player, with origin-scoped request context. */
data class DetectedStream(val uri: String,val mimeType: String,val label: String,val requestScopes: List<RequestScope>)
