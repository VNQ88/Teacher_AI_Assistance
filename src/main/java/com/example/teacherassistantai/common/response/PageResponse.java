package com.example.teacherassistantai.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;


@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> implements Serializable {
    int pageNo;
    int pageSize;
    int totalPage;
    T items;
}
