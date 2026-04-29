-- V9: Add shipping address fields to orders table (MySQL 8.x)
ALTER TABLE orders ADD COLUMN shipping_name    VARCHAR(64);
ALTER TABLE orders ADD COLUMN shipping_phone   VARCHAR(20);
ALTER TABLE orders ADD COLUMN shipping_address TEXT;
