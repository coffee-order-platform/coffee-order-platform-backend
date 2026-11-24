#!/bin/sh
# 테스트 전용 스키마(coffee_order_test)를 만들고 앱 계정에 권한을 준다. src/test/resources/config/application.yml 이 이 스키마를 쓴다.
# MySQL 이미지는 볼륨이 비어 있을 때(처음 켤 때)만 이 폴더를 실행한다. 이미 쓰던 볼륨이면 저장소 루트에서 한 번 실행한다:
#   docker compose exec -T mysql sh < docker/mysql-init/01-test-schema.sh
mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS coffee_order_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON coffee_order_test.* TO '$MYSQL_USER'@'%';
SQL
