-- V2__Perfume_Multiple_Categories.sql
-- Un perfume puede tener mas de una categoria. Migra la columna "category" (una sola,
-- fija) a una tabla aparte que soporta varias por perfume, incluyendo categorias libres
-- que el admin agregue ademas de las 8 fijas.

CREATE TABLE perfume_categories (
    perfume_id BIGINT NOT NULL REFERENCES perfumes(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    category VARCHAR(100) NOT NULL,
    PRIMARY KEY (perfume_id, position)
);

INSERT INTO perfume_categories (perfume_id, position, category)
SELECT id, 0, category FROM perfumes;

DROP INDEX IF EXISTS idx_perfumes_category;
ALTER TABLE perfumes DROP COLUMN category;

CREATE INDEX idx_perfume_categories_category ON perfume_categories (category);
