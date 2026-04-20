package com.example.teacherassistantai.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Standard API envelope containing status, message, and optional data payload")
public class ResponseData<T> {
    @Schema(description = "HTTP-like status code returned by the API", example = "200")
    private  int status;

    @Schema(description = "Human-readable result message", example = "Chat messages")
    private  String message;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Response payload; structure depends on each endpoint")
    private T data;

    /**
     * Response data for the API to retrieve data successfully. For GET, POST only
     * @param status
     * @param message
     * @param data
     */
    public ResponseData(int status, String message, T data) {
        this.status = status;
        this.message = message;
        this.data = data;
    }

    /**
     * Response data when the API executes successfully or getting error. For PUT, PATCH, DELETE
     * @param status
     * @param message
     */
    public ResponseData(int status, String message) {
        this.status = status;
        this.message = message;
    }

}