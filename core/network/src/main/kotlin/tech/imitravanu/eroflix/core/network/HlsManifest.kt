package tech.imitravanu.eroflix.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HlsVariant(val uri: String,val height: Int?,val bandwidth: Int?,val label: String)
data class HlsManifest(val resources: Set<String>,val variants: List<HlsVariant>,val unsupportedEncryption: Boolean,val live: Boolean) {
    companion object {
        private val attribute=Regex("([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]*))(?:,|$)")
        fun parse(base: String,text: String): HlsManifest {
            require(text.length<=512*1024 && text.trimStart('\uFEFF',' ','\r','\n','\t').startsWith("#EXTM3U"))
            val root=base.toHttpUrlOrNull() ?: error("Invalid playlist URL")
            val resources=linkedSetOf<String>();val variants=mutableListOf<HlsVariant>();var pending: Map<String,String>?=null;var encrypted=false
            val lines=text.lines();require(lines.size<=10_000)
            fun uri(value: String): String { require(value.isNotBlank() && value.length<=4096);return root.resolve(value)?.toString() ?: throw IllegalArgumentException("Invalid playlist resource") }
            for(raw in lines) {
                val line=raw.trim();if(line.isEmpty()) continue
                if(line.startsWith("#EXT-X-STREAM-INF:")) {
                    require(pending==null);pending=attribute.findAll(line.substringAfter(':')).associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3]) };continue
                }
                if(line.startsWith("#")) {
                    if(line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                        val fields=attribute.findAll(line.substringAfter(':')).associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3]) }
                        val method=fields["METHOD"]
                        if(method !in setOf("NONE","AES-128") || fields["KEYFORMAT"]?.let { it!="identity" }==true) encrypted=true
                        if(method=="AES-128") require(fields["URI"]!=null)
                    }
                    val unsupportedKey=encrypted && (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:"))
                    for(m in attribute.findAll(line.substringAfter(':',""))) if(m.groupValues[1]=="URI" && !unsupportedKey) resources+=uri(m.groups[2]?.value ?: m.groupValues[3])
                    continue
                }
                val link=uri(line);resources+=link
                pending?.let { fields ->
                    val resolution=fields["RESOLUTION"]?.split('x');val height=resolution?.getOrNull(1)?.toIntOrNull();val bandwidth=fields["BANDWIDTH"]?.toIntOrNull()
                    variants+=HlsVariant(link,height,bandwidth,height?.let { "${it}p" } ?: "Source variant");pending=null
                }
            }
            require(pending==null && resources.size<=4096)
            return HlsManifest(resources,variants,encrypted,variants.isEmpty() && lines.any { it.startsWith("#EXTINF:") } && lines.none { it.trim()=="#EXT-X-ENDLIST" })
        }
    }
}
