-- Local demonstration only. All three passwords are Demo-only-123! (BCrypt).
INSERT INTO app_users VALUES
 (1,'customer-a','$2a$10$vxqMMWXggfOAqQCGjYzKJOLY/6cAj.qydxZovtO9Lq0ZmAUB3gTRm','CUSTOMER'),
 (2,'customer-b','$2a$10$vxqMMWXggfOAqQCGjYzKJOLY/6cAj.qydxZovtO9Lq0ZmAUB3gTRm','CUSTOMER'),
 (3,'admin','$2a$10$vxqMMWXggfOAqQCGjYzKJOLY/6cAj.qydxZovtO9Lq0ZmAUB3gTRm','ADMIN');
INSERT INTO products VALUES
 (101,'NOTE-A5','A5 Notebook',12.50,100),
 (102,'PEN-BLUE','Blue Pen',3.20,100),
 (103,'MUG-WHITE','White Mug',24.00,100),
 (104,'BAG-CANVAS','Canvas Bag',35.90,100),
 (105,'DESK-MAT','Desk Mat',45.00,100);
