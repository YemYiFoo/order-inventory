CREATE TABLE app_users (
  id BIGINT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(16) NOT NULL CHECK (role IN ('CUSTOMER','ADMIN'))
) ENGINE=InnoDB;
CREATE TABLE products (
  id BIGINT PRIMARY KEY CHECK (id > 0),
  sku VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  price DECIMAL(12,2) NOT NULL CHECK (price > 0),
  available_stock INT NOT NULL CHECK (available_stock >= 0)
) ENGINE=InnoDB;
CREATE TABLE orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  canonical_request VARCHAR(1024) CHARACTER SET ascii NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('PLACED','CANCELLED','SHIPPED')),
  currency CHAR(3) NOT NULL CHECK (currency = 'MYR'),
  total_amount DECIMAL(18,2) NOT NULL CHECK (total_amount >= 0),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES app_users(id),
  CONSTRAINT uk_orders_customer_key UNIQUE (customer_id, idempotency_key),
  INDEX ix_orders_history (customer_id, created_at DESC, id DESC)
) ENGINE=InnoDB;
CREATE TABLE order_items (
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL CHECK (quantity BETWEEN 1 AND 1000),
  sku VARCHAR(40) NOT NULL,
  name VARCHAR(120) NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL CHECK (unit_price > 0),
  line_total DECIMAL(18,2) NOT NULL CHECK (line_total > 0),
  PRIMARY KEY (order_id, product_id),
  CONSTRAINT fk_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT fk_items_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB;
