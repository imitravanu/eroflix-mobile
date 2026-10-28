package tech.imitravanu.eroflix.core.data
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
/** P02 owns connection leasing, schema and generation activation above this driver. */
fun createCatalogDriver(): SQLiteDriver = BundledSQLiteDriver()
