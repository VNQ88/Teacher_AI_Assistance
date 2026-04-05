package com.example.teacherassistantai.service;

import com.example.teacherassistantai.dto.request.CreateClassroomRequest;
import com.example.teacherassistantai.dto.request.UpdateClassroomRequest;
import com.example.teacherassistantai.dto.response.ClassroomResponse;
import com.example.teacherassistantai.dto.response.UserResponse;
import com.example.teacherassistantai.entity.Classroom;
import com.example.teacherassistantai.entity.Subject;
import com.example.teacherassistantai.entity.User;
import com.example.teacherassistantai.exception.InvalidDataException;
import com.example.teacherassistantai.exception.ResourceNotFoundException;
import com.example.teacherassistantai.mapper.UserMapper;
import com.example.teacherassistantai.repository.ClassroomRepository;
import com.example.teacherassistantai.repository.SubjectRepository;
import com.example.teacherassistantai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ClassroomService {

    private final ClassroomRepository classroomRepository;
    private final SubjectRepository subjectRepository;
    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public List<ClassroomResponse> getAllClassrooms(Long subjectId) {
        List<Classroom> classrooms = subjectId == null
                ? classroomRepository.findAll()
                : classroomRepository.findBySubjectId(subjectId);
        return classrooms.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ClassroomResponse getClassroomById(Long classroomId) {
        Classroom classroom = findClassroom(classroomId);
        return toResponse(classroom);
    }

    @Transactional
    public ClassroomResponse createClassroom(CreateClassroomRequest request) {
        if (classroomRepository.existsByCodeIgnoreCase(request.getCode())) {
            throw new InvalidDataException("Classroom code already exists");
        }

        Subject subject = subjectRepository.findById(request.getSubjectId())
                .orElseThrow(() -> new ResourceNotFoundException("Subject not found with id: " + request.getSubjectId()));
        User teacher = userRepository.findById(request.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher not found with id: " + request.getTeacherId()));

        validateTeacherRole(teacher);

        Classroom classroom = Classroom.builder()
                .name(request.getName().trim())
                .code(request.getCode().trim().toUpperCase())
                .academicYear(request.getAcademicYear().trim())
                .semester(request.getSemester())
                .description(request.getDescription())
                .subject(subject)
                .teacher(teacher)
                .active(true)
                .build();

        return toResponse(classroomRepository.save(classroom));
    }

    @Transactional
    public ClassroomResponse updateClassroom(Long classroomId, UpdateClassroomRequest request) {
        Classroom classroom = findClassroom(classroomId);

        classroom.setName(request.getName().trim());
        classroom.setAcademicYear(request.getAcademicYear().trim());
        classroom.setSemester(request.getSemester());
        classroom.setDescription(request.getDescription());
        classroom.setActive(request.getActive());

        return toResponse(classroomRepository.save(classroom));
    }

    @Transactional
    public void addStudent(Long classroomId, Long studentId) {
        Classroom classroom = findClassroom(classroomId);
        User student = userRepository.findById(studentId)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found with id: " + studentId));

        classroom.getStudents().add(student);
        classroomRepository.save(classroom);
    }

    @Transactional
    public void removeStudent(Long classroomId, Long studentId) {
        Classroom classroom = findClassroom(classroomId);
        boolean removed = classroom.getStudents().removeIf(student -> student.getId().equals(studentId));
        if (!removed) {
            throw new ResourceNotFoundException("Student is not enrolled in classroom");
        }
        classroomRepository.save(classroom);
    }

    @Transactional
    public void deleteClassroom(Long classroomId) {
        Classroom classroom = findClassroom(classroomId);
        classroomRepository.delete(classroom);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getStudentsByClassroom(Long classroomId) {
        Classroom classroom = findClassroom(classroomId);
        return classroom.getStudents().stream()
                .map(userMapper::toUserResponse)
                .toList();
    }

    private Classroom findClassroom(Long classroomId) {
        return classroomRepository.findById(classroomId)
                .orElseThrow(() -> new ResourceNotFoundException("Classroom not found with id: " + classroomId));
    }

    private void validateTeacherRole(User teacher) {
        boolean isTeacher = teacher.getRoles() != null && teacher.getRoles().stream()
                .anyMatch(role -> "TEACHER".equalsIgnoreCase(role.getName()) || "ADMIN".equalsIgnoreCase(role.getName()));
        if (!isTeacher) {
            throw new InvalidDataException("Selected user does not have TEACHER role");
        }
    }

    private ClassroomResponse toResponse(Classroom classroom) {
        return ClassroomResponse.builder()
                .id(classroom.getId())
                .name(classroom.getName())
                .code(classroom.getCode())
                .academicYear(classroom.getAcademicYear())
                .semester(classroom.getSemester())
                .description(classroom.getDescription())
                .active(classroom.getActive())
                .subjectId(classroom.getSubject().getId())
                .subjectName(classroom.getSubject().getName())
                .teacherId(classroom.getTeacher().getId())
                .teacherName(classroom.getTeacher().getFullName())
                .studentCount(classroom.getStudents().size())
                .build();
    }
}

