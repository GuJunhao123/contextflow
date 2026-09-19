package com.contextflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VectorStoreTest {
    @Test void vectorSqlFiltersVersionModelYearAndLimitsThirty() throws Exception {
        var gateway = new ModelGateway(new MockEnvironment().withProperty("contextflow.vector.dimensions", "3")
                .withProperty("contextflow.vector.model", "embed").withProperty("contextflow.vector.embedding-url", "http://localhost/embeddings"), new ObjectMapper());
        Connection connection = mock(Connection.class);
        PreparedStatement manifest = mock(PreparedStatement.class), count = mock(PreparedStatement.class), query = mock(PreparedStatement.class);
        ResultSet metadata = mock(ResultSet.class), counts = mock(ResultSet.class), rows = mock(ResultSet.class);
        when(connection.prepareStatement(startsWith("SELECT dimensions"))).thenReturn(manifest);
        when(connection.prepareStatement(startsWith("SELECT count"))).thenReturn(count);
        when(connection.prepareStatement(contains("LIMIT 30"))).thenReturn(query);
        when(manifest.executeQuery()).thenReturn(metadata); when(metadata.next()).thenReturn(true);
        when(metadata.getInt(1)).thenReturn(3); when(metadata.getInt(3)).thenReturn(12);
        when(metadata.getString(2)).thenReturn("embed|http://localhost/embeddings");
        when(count.executeQuery()).thenReturn(counts); when(counts.next()).thenReturn(true); when(counts.getInt(1)).thenReturn(12);
        when(query.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false); when(rows.getString(1)).thenReturn("CHUNK-1");
        VectorStore store = new VectorStore(gateway) { @Override Connection connect() { return connection; } };
        assertEquals(List.of("CHUNK-1"), store.search("v1", 12, "Sedan", 2023, new float[]{1,2,3}));
        verify(connection).prepareStatement(contains("year_from<=? AND year_to>=? ORDER BY embedding <=> CAST(? AS vector), chunk_id LIMIT 30"));
        verify(query).setString(1, "v1"); verify(query).setString(2, "Sedan");
        verify(query).setInt(3, 2023); verify(query).setInt(4, 2023); verify(query).setString(5, "[1.0,2.0,3.0]");
        when(metadata.getInt(1)).thenReturn(4);
        assertEquals("INDEX_NOT_READY", assertThrows(DiagnosisService.Failure.class, () -> store.search("v1", 12, "Sedan", 2023, new float[]{1,2,3})).code);
    }
}
