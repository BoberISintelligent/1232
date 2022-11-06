package components.form.validation

object IntConstraints {

    class MustBeInt : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String) = when {
            value.toIntOrNull() == null -> violation("$fieldNameForMessage peab olema arv")
            else -> null
        }
    }

    class MinMax(
        private val min: Int = Int.MIN_VALUE,
        private val max: Int = Int.MAX_VALUE,
    ) : FieldConstraint<String>() {
        override fun validate(value: String, fieldNameForMessage: String): ConstraintViolation<String>? {
            val int = value.toIntOrNull() ?: return null
            return when {
                int < min || int > max -> violation("$fieldNameForMessage peab olema vahemikus $min–$max")
                else -> null
            }
        }
    }
}