package org.batfish.datamodel.vendor_family.f5_bigip;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Configuration for a {@link Pool} within a {@link HaGroup}. */
public final class HaGroupPool implements Serializable {

  public static final class Builder {

    public @Nonnull HaGroupPool build() {
      checkArgument(_name != null, "Missing name");
      return new HaGroupPool(_name, _weight);
    }

    public @Nonnull Builder setName(String name) {
      _name = name;
      return this;
    }

    public @Nonnull Builder setWeight(@Nullable Integer weight) {
      _weight = weight;
      return this;
    }

    private @Nullable String _name;
    private @Nullable Integer _weight;

    private Builder() {}
  }

  public static @Nonnull Builder builder() {
    return new Builder();
  }

  public @Nonnull String getName() {
    return _name;
  }

  public @Nullable Integer getWeight() {
    return _weight;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof HaGroupPool)) {
      return false;
    }
    HaGroupPool rhs = (HaGroupPool) obj;
    return _name.equals(rhs._name) && Objects.equals(_weight, rhs._weight);
  }

  @Override
  public int hashCode() {
    return Objects.hash(_name, _weight);
  }

  private final @Nonnull String _name;
  private final @Nullable Integer _weight;

  private HaGroupPool(String name, @Nullable Integer weight) {
    _name = name;
    _weight = weight;
  }
}
