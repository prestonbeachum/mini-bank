package dev.minibank.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Length in Unicode code points, the way JSON Schema's {@code maxLength} counts. {@code @Size}
 * counts UTF-16 units, so "𝐓" is 1 character to the spec but 2 to {@code @Size}.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxCodePoints.Validator.class)
public @interface MaxCodePoints {

    int value();

    String message() default "must be at most {value} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxCodePoints, String> {

        private int max;

        @Override
        public void initialize(MaxCodePoints annotation) {
            this.max = annotation.value();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.codePointCount(0, value.length()) <= max;
        }
    }
}
