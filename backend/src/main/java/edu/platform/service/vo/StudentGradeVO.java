package edu.platform.service.vo;

/** 学生名单 / 成绩录入行：{@code {studentId, realName, major, grade}}。 */
public record StudentGradeVO(String studentId, String realName, String major, Double grade) {
}
