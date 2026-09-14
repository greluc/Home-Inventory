/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Which plugin contract a core serves, and whether a plugin's range covers it (REQ-PLG-008).
 *
 * <h2>A range, not a point</h2>
 *
 * <p>A manifest says {@code contract: ">=1.0.0 <2.0.0"} — what it can work against — and the core
 * says which single version it serves. The question is whether the one falls in the other, and the
 * answer decides whether the plugin is registered at all. A plugin outside the range is
 * <b>disabled and reported, and the core starts normally</b>: foreign code must never prevent the
 * system from starting (09 §9.11).
 *
 * <h2>Why this and not a SemVer library</h2>
 *
 * <p>This module is Apache-2.0 and depends on as little as it can (ADR-0018), and a plugin author's
 * SDK inherits every one of those dependencies. The grammar a manifest actually uses is a
 * conjunction of comparisons — {@code >=1.0.0 <2.0.0} — and that is small enough to read in one
 * sitting, which matters more here than covering ranges nobody writes. Anything it does not
 * understand is <b>refused</b> rather than assumed to match, so the failure of this decision is a
 * plugin that does not start rather than one that starts against the wrong contract.
 */
public final class ContractVersion {

  /**
   * The contract this core serves.
   *
   * <p>Major 1, because the protobuf package is {@code home_inv.plugin.v1} and 09 §9.11 puts the
   * SemVer on that name. It moves when the contract does: a new optional field or method is a
   * minor, and a break is {@code v2} beside {@code v1} for at least six months.
   */
  public static final String SERVED = "1.0.0";

  private ContractVersion() {
    // Constants and one question.
  }

  /**
   * Whether a manifest's range covers the version this core serves.
   *
   * @param range the manifest's {@code spec.contract}
   * @return true when this core can talk to that plugin
   * @throws InvalidManifestException when the range is not a grammar this reads — refused rather
   *     than assumed, because assuming it matches is how a plugin runs against a contract it was
   *     never built for
   */
  public static boolean covers(String range) {
    return covers(range, SERVED);
  }

  /**
   * Whether a range covers one version.
   *
   * @param range a conjunction of comparisons, such as {@code >=1.0.0 <2.0.0}
   * @param version the version to test, as {@code major.minor.patch}
   * @return true when every comparison holds
   * @throws InvalidManifestException when either cannot be read
   */
  public static boolean covers(String range, String version) {
    if (range == null || range.isBlank()) {
      throw new InvalidManifestException(
          "spec.contract is empty. A plugin says which contract versions it works against, as a"
              + " range such as '>=1.0.0 <2.0.0'.");
    }
    int[] served = parse(version, "the served contract version");
    for (String comparison : range.trim().split("\\s+")) {
      if (!holds(comparison, served, range)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether one comparison holds.
   *
   * @param comparison one term of the range
   * @param version the version to test, already parsed
   * @param range the whole range, for the refusal's message
   * @return true when it holds
   */
  private static boolean holds(String comparison, int[] version, String range) {
    String operator;
    String literal;
    if (comparison.startsWith(">=") || comparison.startsWith("<=")) {
      operator = comparison.substring(0, 2);
      literal = comparison.substring(2);
    } else if (comparison.startsWith(">") || comparison.startsWith("<") || comparison.startsWith("=")) {
      operator = comparison.substring(0, 1);
      literal = comparison.substring(1);
    } else {
      operator = "=";
      literal = comparison;
    }

    int order = compare(version, parse(literal, "spec.contract '" + range + "'"));
    return switch (operator) {
      case ">=" -> order >= 0;
      case "<=" -> order <= 0;
      case ">" -> order > 0;
      case "<" -> order < 0;
      default -> order == 0;
    };
  }

  /**
   * Reads {@code major.minor.patch}.
   *
   * @param version the literal
   * @param where what to call it in a refusal
   * @return the three numbers
   * @throws InvalidManifestException when it is not three numbers
   */
  private static int[] parse(String version, String where) {
    List<Integer> parts = new ArrayList<>();
    for (String part : version.trim().split("\\.")) {
      try {
        parts.add(Integer.parseInt(part));
      } catch (NumberFormatException notANumber) {
        throw new InvalidManifestException(
            where + " holds '" + version + "', which is not a version of the form major.minor.patch");
      }
    }
    if (parts.size() != 3) {
      throw new InvalidManifestException(
          where + " holds '" + version + "', which is not a version of the form major.minor.patch");
    }
    return new int[] {parts.get(0), parts.get(1), parts.get(2)};
  }

  /**
   * Orders two versions.
   *
   * @param left one
   * @param right the other
   * @return negative, zero or positive as {@code left} is below, equal to or above {@code right}
   */
  private static int compare(int[] left, int[] right) {
    for (int part = 0; part < 3; part++) {
      int order = Integer.compare(left[part], right[part]);
      if (order != 0) {
        return order;
      }
    }
    return 0;
  }
}
