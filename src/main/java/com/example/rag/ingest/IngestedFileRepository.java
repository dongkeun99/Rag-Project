package com.example.rag.ingest;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 어떤 파일이 어떤 내용(해시)으로 적재됐는지 기록하는 테이블.
 * 이 기록이 있어야 "이미 넣은 파일인지", "내용이 바뀐 파일인지"를 판단할 수 있다.
 */
@Repository
public class IngestedFileRepository {

    private final JdbcTemplate jdbc;

    public IngestedFileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void createTableIfNotExists() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ingested_file (
                    file_name   TEXT PRIMARY KEY,
                    file_hash   TEXT NOT NULL,
                    chunk_count INT  NOT NULL,
                    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    }

    /** 이미 적재된 파일이면 저장돼 있던 해시를 돌려준다. */
    public Optional<String> findHash(String fileName) {
        List<String> rows = jdbc.query(
                "SELECT file_hash FROM ingested_file WHERE file_name = ?",
                (rs, rowNum) -> rs.getString("file_hash"),
                fileName);
        return rows.stream().findFirst();
    }

    public void upsert(String fileName, String fileHash, int chunkCount) {
        jdbc.update("""
                INSERT INTO ingested_file (file_name, file_hash, chunk_count, ingested_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (file_name) DO UPDATE
                SET file_hash   = EXCLUDED.file_hash,
                    chunk_count = EXCLUDED.chunk_count,
                    ingested_at = now()
                """, fileName, fileHash, chunkCount);
    }

    public void delete(String fileName) {
        jdbc.update("DELETE FROM ingested_file WHERE file_name = ?", fileName);
    }

    public List<IngestedFile> findAll() {
        return jdbc.query("""
                SELECT file_name, file_hash, chunk_count
                FROM ingested_file
                ORDER BY file_name
                """, (rs, rowNum) -> new IngestedFile(
                rs.getString("file_name"),
                rs.getString("file_hash"),
                rs.getInt("chunk_count")));
    }

    public record IngestedFile(String fileName, String fileHash, int chunkCount) {
    }
}