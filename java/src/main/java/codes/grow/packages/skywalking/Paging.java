package codes.grow.packages.skywalking;

import codes.grow.packages.skywalking.core.ValidationException;

/**
 * 1-based page selection of native trace and log queries. OAP returns no total and no cursor: a
 * page that comes back full may have a successor.
 *
 * @param pageNum page number (1-based, default 1)
 * @param pageSize page size (1..10000, default 20)
 */
public record Paging(int pageNum, int pageSize) {
  /** Default page size. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  /** First page of {@value #DEFAULT_PAGE_SIZE}. */
  public static final Paging DEFAULT = new Paging(1, DEFAULT_PAGE_SIZE);

  /**
   * Page {@code pageNum} of {@code pageSize}.
   *
   * @param pageNum page number
   * @param pageSize page size
   * @return the paging
   */
  public static Paging of(int pageNum, int pageSize) {
    return new Paging(pageNum, pageSize);
  }

  void validate(String operation) {
    if (pageNum < 1) {
      throw new ValidationException(operation + ": pageNum must be a positive integer");
    }
    if (pageSize < 1 || pageSize > 10_000) {
      throw new ValidationException(operation + ": pageSize must be 1..10000");
    }
  }
}
