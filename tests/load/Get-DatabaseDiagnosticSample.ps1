. (Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'deploy/local/Environment.Common.ps1')
# Local root access is needed for performance_schema across app sessions.
# Credentials stay inside the container; raw SQL text is never exported.
$sampleScript = @'
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
mysql --protocol=TCP --connect-timeout=3 -h127.0.0.1 -uroot -D"$MYSQL_DATABASE" --batch --raw --skip-column-names <<'SQL'
SET SESSION MAX_EXECUTION_TIME=1000;
SELECT JSON_OBJECT('kind','server','observedAt',DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ'),
 'performanceSchema',@@performance_schema,
 'threadsConnected',(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'),
 'threadsRunning',(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'),
 'rowLockCurrentWaits',(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_current_waits'),
 'rowLockWaits',(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'),
 'rowLockTimeMs',(SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_time'),
 'statementCurrentEnabled',(SELECT ENABLED FROM performance_schema.setup_consumers WHERE NAME='events_statements_current'));
SELECT JSON_OBJECT('kind','lock_wait','observedAt',DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ'),
 'requestThread',w.REQUESTING_THREAD_ID,'blockingThread',w.BLOCKING_THREAD_ID,
 'requestTransaction',w.REQUESTING_ENGINE_TRANSACTION_ID,'blockingTransaction',w.BLOCKING_ENGINE_TRANSACTION_ID,
 'table',l.OBJECT_NAME,'index',l.INDEX_NAME,'mode',l.LOCK_MODE,'type',l.LOCK_TYPE,'status',l.LOCK_STATUS)
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
WHERE l.OBJECT_SCHEMA=DATABASE() LIMIT 40;
SELECT JSON_OBJECT('kind','session','observedAt',DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%fZ'),
 'thread',t.THREAD_ID,'connection',t.PROCESSLIST_ID,'command',t.PROCESSLIST_COMMAND,
 'seconds',t.PROCESSLIST_TIME,'state',t.PROCESSLIST_STATE,
 'statementMs',ROUND(e.TIMER_WAIT/1000000000,3),'digest',LEFT(e.DIGEST_TEXT,400))
FROM performance_schema.threads t
LEFT JOIN performance_schema.events_statements_current e ON e.THREAD_ID=t.THREAD_ID
WHERE t.PROCESSLIST_DB=DATABASE() AND t.PROCESSLIST_ID<>CONNECTION_ID() AND t.PROCESSLIST_COMMAND<>'Sleep'
ORDER BY t.PROCESSLIST_TIME DESC LIMIT 50;
SQL
'@
Invoke-LocalContainerScript -Service mysql -Script $sampleScript
