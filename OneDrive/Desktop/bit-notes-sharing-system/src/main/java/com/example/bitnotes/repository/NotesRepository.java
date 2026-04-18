package com.example.bitnotes.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.bitnotes.model.Notes;
public interface NotesRepository extends JpaRepository<Notes, Long> {
}