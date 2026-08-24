package com.food.ordering.system.order.service.domain.dto.create;

import lombok.*;
import lombok.extern.jackson.Jacksonized;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Getter
@Builder
@Jacksonized
@AllArgsConstructor
public class OrderAddress {
    @NotNull
    @Size(max = 50)
    private final String street;
    @NotNull
    @Size(max = 10)
    private final String postalCode;
    @NotNull
    @Size(max = 50)
    private final String city;
}
