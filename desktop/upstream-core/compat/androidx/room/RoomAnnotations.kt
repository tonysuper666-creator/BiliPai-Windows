package androidx.room

/**
 * Compile surface for the Room annotations on upstream entities and DAO interfaces. Annotations carry no behavior;
 * Room's generated implementation does, and the desktop has none: every DAO the desktop uses is implemented by the
 * desktop itself (JSON stores such as DesktopBlockedUpStore). The Room database classes are desktop-owned
 * (appCoreDesktopOwned), so nothing here can open a database.
 */
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Dao
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Entity(val tableName: String = "", val primaryKeys: Array<String> = [], val indices: Array<Index> = [])
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class ColumnInfo(val name: String = "", val defaultValue: String = "")
@Retention(AnnotationRetention.BINARY)
annotation class Index(vararg val value: String = [], val name: String = "", val unique: Boolean = false)
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Insert(val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Update(val onConflict: Int = OnConflictStrategy.ABORT)
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Upsert
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Delete
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Transaction

object OnConflictStrategy { const val NONE = 0; const val REPLACE = 1; const val ROLLBACK = 2; const val ABORT = 3; const val FAIL = 4; const val IGNORE = 5 }
