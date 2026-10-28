package tech.imitravanu.eroflix.core.network

/** Bounded HTML attribute inspection. No scripts, comments, lazy URLs or event handlers run. */
object PublicSourceTags {
    private val attribute=Regex("(?:^|\\s)([A-Za-z_:][A-Za-z0-9_:.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))")
    fun extract(html: String): List<String> {
        val text=html.take(256*1024)
        // ASCII folding preserves source offsets (Unicode lowercasing can expand characters).
        val lower=String(CharArray(text.length) { i -> text[i].let { if(it in 'A'..'Z') (it.code+32).toChar() else it } })
        val result=mutableListOf<String>();var cursor=0
        while(cursor<text.length && result.size<16) {
            val start=text.indexOf('<',cursor);if(start<0) break
            if(text.startsWith("<!--",start)) { val end=text.indexOf("-->",start+4);if(end<0) break;cursor=end+3;continue }
            val end=tagEnd(text,start) ?: break
            var nameEnd=start+1
            while(nameEnd<end && text[nameEnd].isLetterOrDigit()) nameEnd++
            val name=if(nameEnd==end || text[nameEnd].isWhitespace() || text[nameEnd]=='/') lower.substring(start+1,nameEnd) else ""
            if(name in setOf("script","style")) {
                val close=lower.indexOf("</$name",end+1);if(close<0) break
                cursor=(tagEnd(text,close) ?: break)+1;continue
            }
            if(name in setOf("video","source")) {
                attribute.findAll(text.substring(nameEnd,end)).firstOrNull { it.groupValues[1].equals("src",true) }?.let { match ->
                    htmlEntities(match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()).takeIf { it.length in 1..4096 }?.let { result+=it }
                }
            }
            cursor=end+1
        }
        return result
    }
    private fun tagEnd(text: String,start: Int): Int? {
        var quote: Char?=null
        for(i in start+1 until minOf(text.length,start+8192)) {
            val c=text[i]
            if(quote!=null) { if(c==quote) quote=null }
            else if(c=='\'' || c=='"') quote=c
            else if(c=='>') return i
        }
        return null
    }
}

/** Only the measured clear KVS configuration shape; opaque/dynamically replaced media stays web-only. */
internal object PublicPlayerConfig {
    private val scripts=Regex("<script\\b[^>]*>(.*?)</script\\s*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL))
    private val declaration=Regex("\\b(?:var|let|const)\\s+flashvars\\s*=\\s*\\{")
    fun extract(family: String,html: String): List<String> {
        if(family!="fullerotic") return emptyList()
        val result=mutableListOf<String>()
        for(script in scripts.findAll(html.take(256*1024)).map { it.groupValues[1] }) {
            if(!Regex("\\bkt_player\\s*\\(").findAll(script).any { isCode(script,it.range.first) } || "generate_mp4" in script) continue
            val start=declaration.findAll(script).firstOrNull { isCode(script,it.range.first) }?.range?.last ?: continue
            val objectText=balancedObject(script,start) ?: continue
            val fields=flatLiteralObject(objectText) ?: continue
            for(key in listOf("video_url","video_alt_url","video_alt_url2")) {
                fields[key]?.let(::htmlEntities)?.takeIf { uri -> uri.none { it.isISOControl() } && (uri.startsWith("https://") || uri.startsWith("/") && !uri.startsWith("//")) }?.let { result+=it }
            }
        }
        return result.distinct().take(3)
    }
    private fun isCode(text: String,index: Int): Boolean {
        var i=0
        while(i<index) {
            val c=text[i]
            if(c in "\"'`") {
                val quote=c;i++
                while(i<text.length) { val current=text[i++];if(current=='\\') i++ else if(current==quote) break }
            } else if(text.startsWith("//",i)) i=text.indexOf('\n',i+2).takeIf { it>=0 } ?: text.length
            else if(text.startsWith("/*",i)) i=text.indexOf("*/",i+2).takeIf { it>=0 }?.plus(2) ?: text.length
            else i++
        }
        return i==index
    }
    private fun balancedObject(text: String,start: Int): String? {
        var quote: Char?=null;var escaped=false;var depth=0
        for(i in start until minOf(text.length,start+32768)) {
            val c=text[i]
            if(quote!=null) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c==quote) quote=null;continue }
            if(c=='\'' || c=='"') { quote=c;continue }
            if(c=='{') depth++
            if(c=='}' && --depth==0) return text.substring(start,i+1)
        }
        return null
    }
    private fun flatLiteralObject(text: String): Map<String,String>? {
        val fields=linkedMapOf<String,String>();var i=1
        fun space() { while(i<text.length && text[i].isWhitespace()) i++ }
        while(i<text.length) {
            space();if(text.getOrNull(i)=='}') return fields
            val key=if(text.getOrNull(i) in listOf('\'','"')) {
                val parsed=stringLiteral(text,i) ?: return null;i=parsed.second;parsed.first
            } else {
                val start=i;while(i<text.length && (text[i].isLetterOrDigit() || text[i] in "_$")) i++
                text.substring(start,i)
            }
            if(key.isEmpty() || key.length>64 || key in fields) return null
            space();if(text.getOrNull(i++)!=':') return null;space()
            val value=if(text.getOrNull(i) in listOf('\'','"')) {
                val parsed=stringLiteral(text,i) ?: return null;i=parsed.second;parsed.first
            } else {
                val start=i;while(i<text.length && text[i]!=',' && text[i]!='}') i++
                text.substring(start,i).trim().takeIf { it.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?|true|false|null")) } ?: return null
            }
            fields[key]=value;space()
            when(text.getOrNull(i)) { ',' -> i++;'}' -> return fields;else -> return null }
            if(fields.size>128) return null
        }
        return null
    }
    private fun stringLiteral(text: String,start: Int): Pair<String,Int>? {
        val quote=text[start];val out=StringBuilder();var i=start+1
        while(i<text.length) {
            val c=text[i++]
            if(c==quote) return out.toString() to i
            if(out.length>=4096 || c.isISOControl()) return null
            if(c!='\\') { out.append(c);continue }
            if(i==text.length) return null
            when(val escape=text[i++]) {
                '\\','/','\'','"' -> out.append(escape)
                'u','x' -> {
                    val count=if(escape=='u') 4 else 2
                    if(i+count>text.length) return null
                    val number=text.substring(i,i+count).toIntOrNull(16) ?: return null
                    out.append(number.toChar());i+=count
                }
                else -> return null
            }
        }
        return null
    }
}

internal fun htmlEntities(value: String): String = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|quot|apos|lt|gt);").replace(value) { match ->
    val key=match.groupValues[1]
    when(key) {
        "amp" -> "&";"quot" -> "\"";"apos" -> "'";"lt" -> "<";"gt" -> ">"
        else -> {
            val number=if(key.startsWith("#x")) key.drop(2).toIntOrNull(16) else key.drop(1).toIntOrNull()
            if(number!=null && number in 1..0x10ffff && number !in 0xd800..0xdfff) String(Character.toChars(number)) else match.value
        }
    }
}
