-- Chạy riêng trên MySQL của cả hai máy; không dùng lại schema của bản cũ.
CREATE DATABASE IF NOT EXISTS lab5_bank CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE lab5_bank;
CREATE TABLE IF NOT EXISTS accounts (
 account VARCHAR(40) PRIMARY KEY, password_hash CHAR(64) NOT NULL,
 balance DECIMAL(19,2) NOT NULL DEFAULT 0, CHECK (balance >= 0)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS ledger_state (
 id INT PRIMARY KEY, sequence_no BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO ledger_state VALUES (1,0);
CREATE TABLE IF NOT EXISTS events (
 sequence_no BIGINT PRIMARY KEY, request_id CHAR(36) NOT NULL UNIQUE,
 account VARCHAR(40) NOT NULL, kind VARCHAR(12) NOT NULL, target VARCHAR(40) NOT NULL,
 amount DECIMAL(19,2) NOT NULL, balance DECIMAL(19,2) NOT NULL,
 target_balance DECIMAL(19,2), event_time VARCHAR(40) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS outbox (
 sequence_no BIGINT PRIMARY KEY, delivered BOOLEAN NOT NULL DEFAULT FALSE,
 attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(1000),
 FOREIGN KEY (sequence_no) REFERENCES events(sequence_no)
) ENGINE=InnoDB;
-- Tài khoản demo: 1001 / 1002, mật khẩu 123456 (SHA-256 chỉ dành cho bài lab).
INSERT IGNORE INTO accounts VALUES
 ('1001', SHA2('123456',256),1000000.00),
 ('1002', SHA2('123456',256),1000000.00);
-- Tạo user riêng ở mỗi máy, ví dụ trong MySQL:
-- CREATE USER 'lab5'@'localhost' IDENTIFIED BY 'change-me';
-- GRANT SELECT,INSERT,UPDATE ON lab5_bank.* TO 'lab5'@'localhost';
