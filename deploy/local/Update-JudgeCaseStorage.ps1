. (Join-Path $PSScriptRoot 'Environment.Common.ps1')
# Widen only TEXT columns, preserving data and accepting existing wider columns.
$schemaScript = @'
export MYSQL_PWD="$MYSQL_PASSWORD"
column_type=$(mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -Nse "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='question' AND COLUMN_NAME='judgeCase'")
case "$column_type" in
  text)
    mysql --protocol=TCP -h127.0.0.1 -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -e 'ALTER TABLE question MODIFY COLUMN judgeCase MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT "Judge cases (JSON array)"'
    ;;
  mediumtext|longtext) ;;
  *) echo 'Unexpected or missing question.judgeCase column; migration stopped.' >&2; exit 1 ;;
esac
echo 'PASS: question.judgeCase supports data larger than 64 KiB.'
'@
Invoke-LocalContainerScript -Service mysql -Script $schemaScript
