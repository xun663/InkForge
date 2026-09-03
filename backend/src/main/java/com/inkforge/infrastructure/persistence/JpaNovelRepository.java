package com.inkforge.infrastructure.persistence;

import com.inkforge.infrastructure.persistence.entity.NovelEntity;
import com.inkforge.novel.Novel;
import com.inkforge.novel.NovelRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** PostgreSQL implementation of the novel port ("postgres" profile). */
@Repository
@Profile("postgres")
@Transactional
public class JpaNovelRepository implements NovelRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public Novel save(Novel novel) {
        return NovelMappers.toDomain(em.merge(NovelMappers.toEntity(novel)));
    }

    @Override
    public Optional<Novel> findById(String id) {
        return Optional.ofNullable(em.find(NovelEntity.class, id))
                .map(NovelMappers::toDomain);
    }

    @Override
    public List<Novel> findAll() {
        return em.createQuery("SELECT n FROM NovelEntity n", NovelEntity.class)
                .getResultList().stream()
                .map(NovelMappers::toDomain)
                .toList();
    }
}
