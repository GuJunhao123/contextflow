package com.contextflow;

import java.sql.*;
import java.util.*;

class VectorStore {
    private final ModelGateway gateway;
    VectorStore(ModelGateway gateway) { this.gateway = gateway; }
    Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", gateway.property("contextflow.vector.username", ""));
        properties.setProperty("password", gateway.property("contextflow.vector.password", ""));
        properties.setProperty("connectTimeout", String.valueOf(Math.max(1, gateway.networkTimeout / 1000)));
        properties.setProperty("socketTimeout", String.valueOf(Math.max(1, gateway.networkTimeout / 1000)));
        return DriverManager.getConnection(gateway.property("contextflow.vector.jdbc-url", ""), properties);
    }
    String identity() { return gateway.property("contextflow.vector.model", "") + "|" + gateway.property("contextflow.vector.embedding-url", ""); }
    static String literal(float[] vector) {
        StringJoiner values = new StringJoiner(",", "[", "]");
        for (float v : vector) values.add(Float.toString(v));
        return values.toString();
    }
    void timeout(Statement statement) throws SQLException { statement.setQueryTimeout(Math.max(1, gateway.networkTimeout / 1000)); }
    void verify(Connection connection, String version, int size) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT dimensions, embedding_identity, chunk_count FROM cf_index_manifest WHERE corpus_version=?")) {
            timeout(statement); statement.setString(1, version);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != gateway.dimensions || !identity().equals(rows.getString(2)) || rows.getInt(3) != size)
                    throw new DiagnosisService.Failure("INDEX_NOT_READY", "向量索引版本/维度/模型不一致，请显式调用/api/index/rebuild。");
            }
        }
        try (var statement = connection.prepareStatement("SELECT count(*) FROM cf_chunks WHERE corpus_version=?")) {
            timeout(statement); statement.setString(1, version);
            try (var rows = statement.executeQuery()) {
                rows.next();
                if (rows.getInt(1) != size) throw new DiagnosisService.Failure("INDEX_NOT_READY", "向量chunk数量不一致，请重建索引。");
            }
        }
    }
    List<String> search(String version, int size, String model, int year, float[] query) throws SQLException {
        try (var connection = connect()) {
            verify(connection, version, size);
            try (var statement = connection.prepareStatement("SELECT chunk_id FROM cf_chunks WHERE corpus_version=? AND model=? AND year_from<=? AND year_to>=? ORDER BY embedding <=> CAST(? AS vector), chunk_id LIMIT 30")) {
                timeout(statement); statement.setString(1, version); statement.setString(2, model); statement.setInt(3, year); statement.setInt(4, year);
                statement.setString(5, literal(query));
                List<String> ids = new ArrayList<>();
                try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
                return ids;
            }
        }
    }
    void rebuild(String version, List<Contracts.Source> sources, List<float[]> embeddings) throws SQLException {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.createStatement()) { timeout(lock); lock.execute("SELECT pg_advisory_xact_lock(74180632)"); }
                try (var delete = connection.prepareStatement("DELETE FROM cf_chunks WHERE corpus_version=?")) {
                    timeout(delete); delete.setString(1, version); delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO cf_chunks(corpus_version,chunk_id,model,year_from,year_to,embedding) VALUES (?,?,?,?,?,CAST(? AS vector))")) {
                    timeout(insert);
                    for (int i = 0; i < sources.size(); i++) {
                        var source = sources.get(i);
                        insert.setString(1, version); insert.setString(2, source.docId()); insert.setString(3, source.model());
                        insert.setInt(4, source.yearFrom()); insert.setInt(5, source.yearTo()); insert.setString(6, literal(embeddings.get(i))); insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (var manifest = connection.prepareStatement("INSERT INTO cf_index_manifest(corpus_version,dimensions,embedding_identity,chunk_count) VALUES (?,?,?,?) ON CONFLICT(corpus_version) DO UPDATE SET dimensions=excluded.dimensions,embedding_identity=excluded.embedding_identity,chunk_count=excluded.chunk_count")) {
                    timeout(manifest); manifest.setString(1, version); manifest.setInt(2, gateway.dimensions); manifest.setString(3, identity()); manifest.setInt(4, sources.size()); manifest.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) { connection.rollback(); throw e; }
        }
    }
}
