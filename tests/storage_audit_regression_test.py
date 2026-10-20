"""Run: python3 tests/storage_audit_regression_test.py (no Android build).
SQL is extracted from production sources; wiring checks are static, not Binder tests.
"""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/io/relimus/zflow'
DAO = (BASE / 'room/FreeFormAppsDao.kt').read_text()
REPO = (BASE / 'room/DatabaseRepository.kt').read_text()
MIGRATIONS = (BASE / 'room/MyDatabase.kt').read_text()

def query(method):
    return re.search(r'@Query\("([^"\n]+)"\)\s+fun ' + method + r'\(', DAO).group(1)

def ddl():
    """Current entity DDL as Room would generate it (migration 7->8 target)."""
    return ('CREATE TABLE FreeFormAppsEntity('
            'id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, '
            'sortNum INTEGER NOT NULL DEFAULT 0, '
            'packageName TEXT NOT NULL, userId INTEGER NOT NULL DEFAULT 0)')

class StorageAuditRegressionTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute(ddl())
        self.addCleanup(self.db.close)

    def insert(self, package, user=0):
        self.db.execute(query('insert'), {'packageName': package, 'userId': user})

    def test_duplicate_insert_and_work_profile(self):
        self.insert('com.example.app')
        self.insert('com.example.app')
        self.insert('com.example.app', 10)
        self.assertEqual(self.db.execute(query('getCount')).fetchone()[0], 2)
        # 新主键独立于排序：重复插入不会产生重复 id
        ids = self.db.execute('SELECT id FROM FreeFormAppsEntity').fetchall()
        self.assertEqual(len(ids), len(set(ids)))

    def test_swap_retains_both_rows(self):
        self.insert('com.example.a')
        self.insert('com.example.b')
        self.db.execute(query('parkSortKeys'))
        for index, package in enumerate(['com.example.b', 'com.example.a']):
            self.db.execute(query('assignSortNum'), {'packageName': package, 'userId': 0, 'sortNum': index})
        self.assertEqual(self.db.execute(query('getAllWithUser')).fetchall(),
                         [(2, 0, 'com.example.b', 0), (1, 1, 'com.example.a', 0)])

    def test_list_reads_have_explicit_order(self):
        for method in ['getAll', 'getAllByFlow', 'getAllWithUser', 'getAllName', 'getAllWithoutLiveData', 'getAllSortNums']:
            self.assertIn('ORDER BY sortNum ASC', query(method))
        self.insert('com.example.a')
        self.db.execute(query('deleteAll'))
        self.assertEqual(self.db.execute(query('getCount')).fetchone()[0], 0)

    def test_insert_and_replace_both_preserve_columns(self):
        # DAO insert 只写 packageName/userId：id 自增、sortNum 默认 0
        self.insert('com.example.a')
        self.insert('com.example.b', 10)
        self.assertEqual(self.db.execute('SELECT packageName, userId FROM FreeFormAppsEntity ORDER BY id').fetchall(),
                         [('com.example.a', 0), ('com.example.b', 10)])
        # 整份替换（ChooseAppsViewModel 路径）显式给 sortNum=index
        self.db.execute(query('deleteAll'))
        self.db.executemany('INSERT INTO FreeFormAppsEntity(sortNum, packageName, userId) VALUES(?,?,?)',
                            [(0, 'com.example.x', 0), (1, 'com.example.y', 5)])
        self.assertEqual(self.db.execute('SELECT sortNum, packageName, userId FROM FreeFormAppsEntity ORDER BY sortNum').fetchall(),
                         [(0, 'com.example.x', 0), (1, 'com.example.y', 5)])

    def test_migration_7_8_rebuilds_table(self):
        # 用旧表结构重新造数据，再按生产顺序完整跑 7->8 迁移
        self.db.execute('DROP TABLE FreeFormAppsEntity')
        self.db.execute('CREATE TABLE FreeFormAppsEntity(sortNum INTEGER NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, userId INTEGER NOT NULL DEFAULT 0)')
        self.db.executemany('INSERT INTO FreeFormAppsEntity(sortNum, packageName, userId) VALUES(?,?,?)',
                            [(0, 'com.a', 0), (1, 'com.b', 10)])
        src = (BASE / 'room/MyDatabase.kt').read_text()
        block = src.split('private val MIGRATION_7_8',1)[1].split('}\n    }\n}',1)[0]
        pat = re.compile(r'db\.execSQL\((.*?)\)\s*(?=\n\s*db\.execSQL|\n\s*})', re.S)
        stmts = [''.join(re.findall(r'"([^"\n]*)"', s)) for s in pat.findall(block)]
        for statement in stmts:
            self.db.execute(statement)
        self.assertEqual(self.db.execute('SELECT id, sortNum, packageName, userId FROM FreeFormAppsEntity ORDER BY id').fetchall(),
                         [(0, 0, 'com.a', 0), (1, 1, 'com.b', 10)])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM FreeFormAppsEntity').fetchone()[0], 2)

    def test_stale_snapshot_guard_precedes_key_parking(self):
        body = REPO.split('suspend fun updateFreeFormSort', 1)[1].split('/** 整体替换', 1)[0]
        self.assertLess(body.index('require('), body.index('parkSortKeys()'))
        self.assertIn('requestedKeys.size == requestedKeys.toSet().size', body)
        self.assertIn('requestedKeys.size == currentKeys.size', body)
        self.assertIn('requestedKeys.toSet() == currentKeys.toSet()', body)

    def test_version_notification_and_observer_wiring(self):
        body = REPO.split('fun bumpVersion', 1)[1]
        self.assertLess(body.index('sp.edit'), body.index('notifyChange'))
        self.assertIn('NotificationSettingsProvider.KEY_APP_LIST_VERSION -> NotificationSettingsProvider.AUTHORITY', body)
        self.assertIn('BlacklistProvider.KEY_BLACKLIST_VERSION -> BlacklistProvider.AUTHORITY', body)
        remote = (BASE / 'providers/RemoteSettings.kt').read_text()
        registration = remote.split('fun registerInvalidationObserver', 1)[1].split('读取一个兼容性', 1)[0]
        self.assertLess(registration.index('registerContentObserver'), registration.index('observerRegistered = true'))
        self.assertIn('registerInvalidationObserver(context)', remote.split('private fun call(', 1)[1])

    def test_bulk_access_both_notification_entrypoints(self):
        source = (BASE / 'providers/NotificationSettingsProvider.kt').read_text()
        self.assertEqual(source.count('ProviderAccess.allowBulkRead(ctx, allowSystemUi = true)'), 2)
        access = (BASE / 'providers/ProviderAccess.kt').read_text()
        self.assertIn('getPackagesForUid(uid)', access)
        self.assertIn('getOrDefault(false)', access)

    def test_launcher_scope_and_activation_hook(self):
        source = (BASE / 'xposed/hook/MainHook.kt').read_text()
        self.assertNotIn('pkg.contains(', source)
        for package in ['com.android.launcher3', 'com.google.android.apps.nexuslauncher']:
            self.assertIn('pkg == "' + package + '"', source)
        self.assertIn('HookMyself.init()', source)
        self.assertIn('return false;', (BASE / 'xposed/hook/utils/HookTest.java').read_text())

    def test_receiver_single_dispatch_and_no_dynamic_registrations(self):
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertLess(manifest.index('FreeformRequestReceiver'), manifest.index('</manifest>'))
        self.assertIn('RequestAuthProvider', manifest)
        self.assertIn('io.relimus.zflow.request.auth', manifest)
        self.assertNotIn('StartFreeformReceiver.register', manifest)
        keep = (BASE / 'service/KeepAliveService.kt').read_text()
        fg = (BASE / 'service/ForegroundService.kt').read_text()
        self.assertNotIn('StartFreeformReceiver()', keep)
        self.assertNotIn('StartFreeformReceiver.register', keep)
        self.assertNotIn('unregisterReceiver', keep)
        self.assertNotIn('registerReceiver', fg)
        receiver = (BASE / 'broadcast/StartFreeformReceiver.kt').read_text()
        self.assertIn('BroadcastAuth.attach', receiver)
        self.assertIn('BroadcastAuth.verify', receiver)

if __name__ == '__main__':
    unittest.main(verbosity=2)
