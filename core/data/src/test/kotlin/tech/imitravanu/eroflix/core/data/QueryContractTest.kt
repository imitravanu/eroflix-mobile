package tech.imitravanu.eroflix.core.data

import org.junit.Test
import org.junit.Assert.*
import tech.imitravanu.eroflix.model.*

class QueryContractTest {
    @Test fun operatorsAreLiteralWordsAndSqlIsParameterized() {
        val p=CatalogSql.predicate(CatalogQuery(search="foo OR bar*",entity=EntityRef(EntityKind.STUDIO,"x'); DROP TABLE titles;--","test")))
        assertTrue(p.args.contains("\"foo\"* AND \"or\"* AND \"bar\"*"))
        assertFalse(p.sql.contains("DROP"));assertTrue(p.args.contains("x'); DROP TABLE titles;--"))
    }
    @Test fun cjkSubstringUsesIndexedCandidateAndUnicodeIsFolded() {
        val p=CatalogSql.predicate(CatalogQuery(search="Café 京物"))
        assertEquals("\"cafe\"*",p.args[0]);assertTrue(p.args.contains("京物"));assertTrue(p.args.contains("%京物%"));assertTrue(p.sql.contains("cjk_grams"))
    }
    @Test fun disjunctiveFacetsRemoveOnlyTheirOwnConstraint() {
        val q=CatalogQuery(countries=setOf(FacetValue.Known("selected-country")),providers=setOf(FacetValue.Known("selected-provider")))
        val p=CatalogSql.predicate(q,"country")
        assertFalse(p.args.contains("selected-country"));assertTrue(p.args.contains("selected-provider"));assertTrue(p.sql.contains("u.hidden=1"))
    }
    @Test fun queryInputLimitsReturnTypedErrors() {
        for(q in listOf(CatalogQuery(search="x".repeat(257)),CatalogQuery(search=(1..17).joinToString(" ")),CatalogQuery(decades=setOf(FacetValue.Known("bad"))))) {
            try { CatalogSql.predicate(q);fail("Should reject invalid query") } catch(e: DataFailure) { assertEquals(ErrorCode.INVALID_QUERY,e.error.code) }
        }
    }
    @Test fun unknownMetadataAndDurationBoundsRemainExplicit() {
        val p=CatalogSql.predicate(CatalogQuery(countries=setOf(FacetValue.Unknown),decades=setOf(FacetValue.Unknown),duration=DurationFilter(1000,2000,false)))
        assertTrue(p.sql.contains("NOT EXISTS (SELECT 1 FROM catalog.title_countries"));assertTrue(p.sql.contains("t.year IS NULL"))
        assertFalse(p.sql.contains("t.duration_ms IS NULL"));assertEquals(listOf(1000L,2000L),p.args)
    }
    @Test fun unknownOnlyDurationDoesNotAdmitKnownRowsOrNumericSentinels() {
        val p=CatalogSql.predicate(CatalogQuery(duration=DurationFilter(onlyUnknown=true)))
        assertTrue(p.sql.contains("t.duration_ms IS NULL"))
        assertFalse(p.sql.contains("t.duration_ms IS NOT NULL"))
        assertTrue(p.args.isEmpty())
    }
    @Test fun membershipCountHandlesByteOrderPartialWordsAndShortMasks() {
        val bits=java.util.BitSet().apply { listOf(1,7,8,63,64,70,130).forEach(::set) }
        val bytes=bits.toByteArray()
        val selected=java.util.BitSet().apply { listOf(7,63,70,129,130).forEach(::set) }
        assertEquals(4L,countMembership(bytes,selected.toLongArray()))
        assertEquals(4L,countMembership(bytes,longArrayOf(-1L)))
        assertEquals(0L,countMembership(bytes,longArrayOf()))
        assertEquals(2L,countMembership(byteArrayOf(0x80.toByte(),0x01),longArrayOf(-1L)))
    }
}
