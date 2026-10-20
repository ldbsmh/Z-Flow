package io.relimus.zflow.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FreeFormAppsEntity::class,
        NotificationAppsEntity::class,
        FreeformBlacklistEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class MyDatabase : RoomDatabase() {
    abstract val freeFormAppsDao: FreeFormAppsDao
    abstract val notificationAppsDao: NotificationAppsDao
    abstract val notificationSyncDao: NotificationSyncDao
    abstract val freeformBlacklistDao: FreeformBlacklistDao

    companion object {
        private var database: MyDatabase? = null

        /**
         * 已移除 allowMainThreadQueries()：主线程访问数据库会直接抛异常，
         * 强迫所有写操作走 [DatabaseRepository] 的挂起方法。
         */
        @Synchronized
        fun getDatabase(context: Context): MyDatabase {
            if (database == null) {
                database = Room.databaseBuilder(
                    context.applicationContext,
                    MyDatabase::class.java,
                    "database.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .build()
            }
            return database!!
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS" +
                            "'CompatibleAppsEntity'" +
                            "('packageName' TEXT NOT NULL, PRIMARY KEY('packageName'))"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS 'CompatibleAppsEntity'")
                db.execSQL(
                    "ALTER TABLE 'FreeFormAppsEntity'" +
                            "ADD 'sortNum' int NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE 'FreeFormAppsEntity' RENAME TO 'FreeFormAppsEntity_OLD'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS" +
                            "'FreeFormAppsEntity'" +
                            "('sortNum' INTEGER NOT NULL, 'packageName' TEXT NOT NULL, PRIMARY KEY('sortNum'))"
                )
                db.execSQL(
                    "INSERT INTO 'FreeFormAppsEntity'('packageName')" +
                            "SELECT packageName from 'FreeFormAppsEntity_OLD'"
                )
                db.execSQL("DROP TABLE 'FreeFormAppsEntity_OLD'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE 'FreeFormAppsEntity' ADD COLUMN 'userId' INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE 'NotificationAppsEntity' ADD COLUMN 'userId' INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS 'FreeformBlacklistEntity' (" +
                            "'packageName' TEXT NOT NULL, " +
                            "'userId' INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY('packageName', 'userId')" +
                            ")"
                )
            }
        }

        /**
         * 6 -> 7：NotificationAppsEntity 主键由 (packageName) 改为 (packageName, userId)。
         * SQLite 不支持修改主键，按 Room 官方做法重建表并迁移数据。
         * 旧数据本身不会重复，因此 INSERT OR IGNORE 不会丢行。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE 'NotificationAppsEntity' RENAME TO 'NotificationAppsEntity_OLD'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS 'NotificationAppsEntity' (" +
                            "'packageName' TEXT NOT NULL, " +
                            "'userId' INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY('packageName', 'userId')" +
                            ")"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO 'NotificationAppsEntity'('packageName', 'userId') " +
                            "SELECT packageName, userId FROM 'NotificationAppsEntity_OLD'"
                )
                db.execSQL("DROP TABLE 'NotificationAppsEntity_OLD'")
            }
        }

        /**
         * 7 -> 8：FreeFormAppsEntity 排序列从主键（自增）拆成普通排序列。
         * SQLite 无法修改主键，重建表：显式单列主键 id，sortNum 只做排序。
         * 这样 insert 与拖拽排序不再争夺同一列（原 4->5 后主键仍是 sortNum，
         * 而 DAO insert 不写 sortNum，隐式自增与 UPDATE 排序互相覆盖）。
         * 已存在的主键值直接作为 id，避免新列与旧值冲突。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE 'FreeFormAppsEntity' RENAME TO 'FreeFormAppsEntity_OLD'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS 'FreeFormAppsEntity' (" +
                            "'id' INTEGER NOT NULL, " +
                            "'sortNum' INTEGER NOT NULL DEFAULT 0, " +
                            "'packageName' TEXT NOT NULL, " +
                            "'userId' INTEGER NOT NULL DEFAULT 0, " +
                            "PRIMARY KEY('id')" +
                            ")"
                )
                db.execSQL(
                    "INSERT INTO 'FreeFormAppsEntity'('id', 'sortNum', 'packageName', 'userId') " +
                            "SELECT sortNum, sortNum, packageName, userId FROM 'FreeFormAppsEntity_OLD'"
                )
                db.execSQL("DROP TABLE 'FreeFormAppsEntity_OLD'")
            }
        }
    }
}
