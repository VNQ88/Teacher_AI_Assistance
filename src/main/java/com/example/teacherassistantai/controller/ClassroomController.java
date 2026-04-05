package com.example.teacherassistantai.controller;

import com.example.teacherassistantai.common.enumerate.ExamStatus;
import com.example.teacherassistantai.common.response.ResponseData;
import com.example.teacherassistantai.dto.request.CreateClassroomRequest;
import com.example.teacherassistantai.dto.request.UpdateClassroomRequest;
import com.example.teacherassistantai.dto.response.ClassroomResponse;
import com.example.teacherassistantai.dto.response.StudentExamResponse;
import com.example.teacherassistantai.dto.response.UserResponse;
import com.example.teacherassistantai.service.ClassroomService;
import com.example.teacherassistantai.service.SubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/classrooms")
@RequiredArgsConstructor
@Validated
@Tag(name = "Classroom Controller")
public class ClassroomController {

    private final ClassroomService classroomService;
    private final SubmissionService submissionService;

    @GetMapping
    @Operation(summary = "Get classrooms")
    public ResponseData<List<ClassroomResponse>> getClassrooms(@RequestParam(required = false) Long subjectId) {
        return new ResponseData<>(HttpStatus.OK.value(), "classrooms", classroomService.getAllClassrooms(subjectId));
    }

    @GetMapping("/{classroomId}")
    @Operation(summary = "Get classroom detail")
    public ResponseData<ClassroomResponse> getClassroom(@PathVariable @Min(1) Long classroomId) {
        return new ResponseData<>(HttpStatus.OK.value(), "classroom", classroomService.getClassroomById(classroomId));
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Create classroom")
    public ResponseData<ClassroomResponse> createClassroom(@RequestBody @Valid CreateClassroomRequest request) {
        log.info("Create classroom with code={}", request.getCode());
        return new ResponseData<>(HttpStatus.CREATED.value(), "Classroom created", classroomService.createClassroom(request));
    }

    @PutMapping("/{classroomId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Update classroom")
    public ResponseData<ClassroomResponse> updateClassroom(@PathVariable @Min(1) Long classroomId,
                                                           @RequestBody @Valid UpdateClassroomRequest request) {
        return new ResponseData<>(HttpStatus.OK.value(), "Classroom updated", classroomService.updateClassroom(classroomId, request));
    }

    @PostMapping("/{classroomId}/students/{studentId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Enroll a student into classroom")
    public ResponseData<Void> addStudent(@PathVariable @Min(1) Long classroomId,
                                         @PathVariable @Min(1) Long studentId) {
        classroomService.addStudent(classroomId, studentId);
        return new ResponseData<>(HttpStatus.OK.value(), "Student enrolled");
    }

    @DeleteMapping("/{classroomId}/students/{studentId}")
    @PreAuthorize("hasAnyAuthority('ADMIN', 'TEACHER')")
    @Operation(summary = "Remove a student from classroom")
    public ResponseData<Void> removeStudent(@PathVariable @Min(1) Long classroomId,
                                            @PathVariable @Min(1) Long studentId) {
        classroomService.removeStudent(classroomId, studentId);
        return new ResponseData<>(HttpStatus.OK.value(), "Student removed");
    }

    @DeleteMapping("/{classroomId}")
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Delete classroom")
    public ResponseData<Void> deleteClassroom(@PathVariable @Min(1) Long classroomId) {
        classroomService.deleteClassroom(classroomId);
        return new ResponseData<>(HttpStatus.OK.value(), "Classroom deleted");
    }

    @GetMapping("/{classroomId}/students")
    @Operation(summary = "Get list of students in classroom")
    public ResponseData<List<UserResponse>> getStudents(@PathVariable @Min(1) Long classroomId) {
        log.info("Get students of classroom id={}", classroomId);
        List<UserResponse> students = classroomService.getStudentsByClassroom(classroomId);
        return new ResponseData<>(HttpStatus.OK.value(), "Students", students);
    }

    @GetMapping("/{classroomId}/exams")
    @Operation(
            summary = "Xem danh sách bài thi của lớp (Student)",
            description = "Trả về danh sách exam của lớp. " +
                    "Mỗi exam có thêm trường mySubmissionStatus cho biết trạng thái bài làm của student đang đăng nhập " +
                    "(NOT_STARTED | IN_PROGRESS | SUBMITTED | ...).")
    public ResponseData<List<StudentExamResponse>> getExamsForStudent(
            @PathVariable @Min(1) Long classroomId,
            @RequestParam(required = false) ExamStatus status) {
        log.info("Request get exams for student: classroomId={}, status={}", classroomId, status);
        return new ResponseData<>(HttpStatus.OK.value(), "Exams",
                submissionService.getExamsForStudent(classroomId, status));
    }
}

