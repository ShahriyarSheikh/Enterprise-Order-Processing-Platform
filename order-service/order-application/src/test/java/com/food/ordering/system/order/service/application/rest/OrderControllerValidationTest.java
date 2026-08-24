package com.food.ordering.system.order.service.application.rest;

import com.food.ordering.system.order.service.application.exception.handler.OrderGlobalExceptionHandler;
import com.food.ordering.system.order.service.domain.ports.input.service.OrderApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerValidationTest {

    private MockMvc mockMvc;

    @Mock
    private OrderApplicationService orderApplicationService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new OrderController(orderApplicationService))
                .setControllerAdvice(new OrderGlobalExceptionHandler())
                .build();
    }

    @Test
    void rejectsNestedAddressConstraintViolationsAsBadRequest() throws Exception {
        String oversizedStreet = "x".repeat(51);
        String request = """
                {
                  "customerId": "d215b5f8-0249-4dc5-89a3-51fd148cfb41",
                  "restaurantId": "d215b5f8-0249-4dc5-89a3-51fd148cfb45",
                  "price": 50.00,
                  "items": [{
                    "productId": "d215b5f8-0249-4dc5-89a3-51fd148cfb48",
                    "quantity": 1,
                    "price": 50.00,
                    "subTotal": 50.00
                  }],
                  "address": {
                    "street": "%s",
                    "postalCode": "10178",
                    "city": "Berlin"
                  }
                }
                """.formatted(oversizedStreet);

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("application/vnd.api.v1+json")
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("address.street: size must be between 0 and 50"));

        verifyNoInteractions(orderApplicationService);
    }

    @Test
    void rejectsMalformedUuidAsBadRequest() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("application/vnd.api.v1+json")
                        .content("{\"customerId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));

        verifyNoInteractions(orderApplicationService);
    }
}
