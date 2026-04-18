package com.bit.notes.repository;

import com.bit.notes.entity.Notes;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotesRepository extends JpaRepository<Notes, Long> {
    boolean existsByAcademicYearIgnoreCaseAndSemesterIgnoreCaseAndSubjectIgnoreCaseAndTitleIgnoreCaseAndOwner_Id(
            String academicYear,
            String semester,
            String subject,
            String title,
            Long ownerId);

    List<Notes> findByOwner_IdOrderByIdDesc(Long ownerId);

    List<Notes> findAllByOrderByIdDesc();
}
