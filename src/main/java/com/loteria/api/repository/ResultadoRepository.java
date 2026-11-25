package com.loteria.api.repository;

import com.loteria.api.entity.Resultado;
import com.loteria.api.entity.ResultadoId;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository para a entidade Resultado.
 * Inclui queries otimizadas para os endpoints da API e invalidação de cache.
 */
@Repository
public interface ResultadoRepository extends JpaRepository<Resultado, ResultadoId> {

    // ============================================================
    // RF07 - Último concurso por modalidade
    // ============================================================
    @Cacheable(value = "latestResultado", key = "#modalidade")
    @Query(value = """
        SELECT * FROM resultados
        WHERE modalidade = :modalidade
        ORDER BY concurso DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<Resultado> findLatestByModalidade(@Param("modalidade") String modalidade);

    // ============================================================
    // RF08 - Concurso específico por modalidade
    // ============================================================
    @Cacheable(value = "resultadoPorConcurso", key = "#modalidade + ':' + #concurso")
    Optional<Resultado> findByModalidadeAndConcurso(String modalidade, Integer concurso);

    // ============================================================
    // RF09 - Lista modalidades com último concurso de cada
    // ============================================================
    @Cacheable(value = "modalidades", unless = "#result.isEmpty()")
    @Query("""
        SELECT r.modalidade, MAX(r.concurso) as ultimoConcurso
        FROM Resultado r
        GROUP BY r.modalidade
        ORDER BY r.modalidade
        """)
    List<Object[]> findModalidadesComUltimoConcurso();

    // ============================================================
    // Busca por data (útil para relatórios)
    // ============================================================
    @Query("""
        SELECT r FROM Resultado r
        WHERE r.modalidade = :modalidade
          AND r.dataApuracao = :data
        """)
    Optional<Resultado> findByModalidadeAndDataApuracao(
            @Param("modalidade") String modalidade,
            @Param("data") LocalDate data);

    // ============================================================
    // Concursos em apuração (status = APURACAO)
    // ============================================================
    @Query("SELECT r FROM Resultado r WHERE r.status = :status ORDER BY r.modalidade, r.concurso")
    List<Resultado> findByStatusOrderByModalidadeConcurso(@Param("status") String status);

    // ============================================================
    // Invalidação de cache (chamada pelo worker ao persistir novo sorteio)
    // ============================================================
    @CacheEvict(value = {"latestResultado", "resultadoPorConcurso", "modalidades"}, allEntries = true)
    @Query(value = "SELECT 1", nativeQuery = true)
    void evictAllCaches();

    // Invalidação específica por modalidade
    @CacheEvict(value = "latestResultado", key = "#modalidade")
    @Query(value = "SELECT 1", nativeQuery = true)
    void evictLatestCache(String modalidade);

    @CacheEvict(value = "resultadoPorConcurso", key = "#modalidade + ':' + #concurso")
    @Query(value = "SELECT 1", nativeQuery = true)
    void evictConcursoCache(String modalidade, Integer concurso);

    // ============================================================
    // Estatísticas
    // ============================================================
    @Query("SELECT COUNT(r) FROM Resultado r WHERE r.modalidade = :modalidade")
    long countByModalidade(@Param("modalidade") String modalidade);

    @Query("SELECT MAX(r.concurso) FROM Resultado r WHERE r.modalidade = :modalidade")
    Integer findMaxConcursoByModalidade(@Param("modalidade") String modalidade);

    @Query("SELECT MIN(r.dataApuracao) FROM Resultado r WHERE r.modalidade = :modalidade")
    LocalDate findMinDataByModalidade(@Param("modalidade") String modalidade);

    @Query("SELECT MAX(r.dataApuracao) FROM Resultado r WHERE r.modalidade = :modalidade")
    LocalDate findMaxDataByModalidade(@Param("modalidade") String modalidade);
}