/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.InsuranceReport;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What an insurer asks for (REQ-LIFE-016).
 *
 * <p>Replacement value per room and in total, with the evidence that backs each line up. The three
 * properties worth holding are that it reports <b>replacement</b> and not the other two figures,
 * that two currencies are two lines and never one, and that it says how many things it left out —
 * a report that quietly omits half a household is worse than one that admits it did.
 *
 * <p>The virus scanner and {@code libvips} are stood in for, borrowed from {@code MediaListingIT}:
 * both are subprocesses this test would otherwise require on every machine that runs it, and
 * neither is what it is about. That an infected file never reaches a report is
 * {@code AttachedEvidence}'s own query and is asserted through the scan state rather than through
 * a scanner.
 */
@DisplayName("The insurance report")
@org.springframework.context.annotation.Import(MediaListingIT.StubUploadDependencies.class)
class InsuranceReportIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");

  @Autowired private InsuranceReport insurance;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private de.greluc.homeinv.media.api.MediaService media;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("reports replacement value per room, and says what it left out")
  void perRoomAndWhatIsMissing() {
    Tenant tenant = newTenant("insurance-rooms@example.org");
    UUID study = aPlace(tenant, "A study", null);
    UUID garage = aPlace(tenant, "A garage", null);

    anItem(tenant, "A camera", study, new Money(new BigDecimal("1200.00"), EUR), 1);
    anItem(tenant, "A lens", study, new Money(new BigDecimal("800.00"), EUR), 1);
    anItem(tenant, "A bicycle", garage, new Money(new BigDecimal("450.00"), EUR), 2);
    // Nobody has said what replacing this would cost.
    anItem(tenant, "A shelf", garage, null, 1);

    InsuranceReport.Report report = inOwn(tenant, () -> insurance.of(null, 50));

    assertThat(report.rooms()).hasSize(2);
    assertThat(report.producedOn()).isNotNull();

    InsuranceReport.Room theStudy =
        report.rooms().stream()
            .filter(room -> room.locationId().equals(study))
            .findFirst()
            .orElseThrow();
    assertThat(theStudy.lines()).extracting(InsuranceReport.Line::name)
        .containsExactlyInAnyOrder("A camera", "A lens");
    assertThat(theStudy.totals()).singleElement()
        .satisfies(total -> assertThat(total.amount()).isEqualByComparingTo("2000.00"));

    // Two of a thing cost twice as much to replace, which a report ignoring the
    // count would understate.
    InsuranceReport.Room theGarage =
        report.rooms().stream()
            .filter(room -> room.locationId().equals(garage))
            .findFirst()
            .orElseThrow();
    assertThat(theGarage.totals()).singleElement()
        .satisfies(total -> assertThat(total.amount()).isEqualByComparingTo("900.00"));

    assertThat(report.totals()).singleElement()
        .satisfies(total -> assertThat(total.amount()).isEqualByComparingTo("2900.00"));

    // The shelf is not in the report and the report says so, rather than leaving
    // somebody to notice after a fire.
    assertThat(report.withoutAReplacementValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("gives two currencies two lines and never one number (REQ-LIFE-017)")
  void twoCurrenciesAreTwoLines() {
    Tenant tenant = newTenant("insurance-currencies@example.org");
    UUID study = aPlace(tenant, "A study", null);
    anItem(tenant, "A camera", study, new Money(new BigDecimal("1200.00"), EUR), 1);
    anItem(tenant, "A microphone", study, new Money(new BigDecimal("300.00"), USD), 1);

    InsuranceReport.Report report = inOwn(tenant, () -> insurance.of(null, 50));

    assertThat(report.totals()).hasSize(2);
    assertThat(report.totals()).extracting(money -> money.currency().getCurrencyCode())
        .containsExactlyInAnyOrder("EUR", "USD");
    // Stated rather than implied: a client can render a sentence and cannot
    // render a missing field.
    assertThat(report.converted()).isFalse();
  }

  @Test
  @DisplayName("carries the photograph and the receipt, and knows which is which")
  void theEvidenceComesWithIt() throws Exception {
    Tenant tenant = newTenant("insurance-evidence@example.org");
    UUID study = aPlace(tenant, "A study", null);
    UUID camera = anItem(tenant, "A camera", study, new Money(new BigDecimal("1200.00"), EUR), 1);

    // Three DIFFERENT pictures. The same bytes would be one media object --
    // storage is content-addressed (ADR-0032) -- and a second attachment of one
    // object to one item is refused by `attachment_unique_live`, so all three
    // would collapse into whichever role the first was given.
    UUID photo = attach(tenant, camera, true, "PHOTO", 1);
    UUID receipt = attach(tenant, camera, false, "RECEIPT", 2);
    attach(tenant, camera, false, "OTHER", 3);
    cleared(tenant);

    // What the rows actually say, before asking the report: a role that did not
    // survive the write would otherwise look like a report that cannot read one.
    assertThat(
            inOwn(
                tenant,
                () ->
                    jdbc.sql("select role from media.attachment order by created_at")
                        .query(String.class)
                        .list()))
        .containsExactly("PHOTO", "RECEIPT", "OTHER");

    InsuranceReport.Line line =
        inOwn(tenant, () -> insurance.of(null, 50)).rooms().getFirst().lines().getFirst();

    // The role is what makes this possible at all: REQ-LIFE-001 had decided an
    // invoice was an attachment like any other, and it was amended on
    // 2026-09-20 so that this report can attach the receipt rather than offering
    // a list and leaving the reader to find it.
    assertThat(line.photo()).isNotNull();
    assertThat(line.photo().mediaObjectId()).isEqualTo(photo);
    assertThat(line.receipts()).singleElement()
        .satisfies(one -> assertThat(one.mediaObjectId()).isEqualTo(receipt));
  }

  @Test
  @DisplayName("reports a subtree when asked for one")
  void aSubtreeOnly() {
    Tenant tenant = newTenant("insurance-subtree@example.org");
    UUID house = aPlace(tenant, "A house", null);
    UUID study = aPlace(tenant, "A study", house);
    UUID shed = aPlace(tenant, "A shed", null);
    anItem(tenant, "A camera", study, new Money(new BigDecimal("1200.00"), EUR), 1);
    anItem(tenant, "A spade", shed, new Money(new BigDecimal("40.00"), EUR), 1);

    InsuranceReport.Report report = inOwn(tenant, () -> insurance.of(house, 50));

    assertThat(report.rooms()).singleElement()
        .satisfies(room -> assertThat(room.locationId()).isEqualTo(study));
    assertThat(report.totals()).singleElement()
        .satisfies(total -> assertThat(total.amount()).isEqualByComparingTo("1200.00"));
  }

  // -------------------------------------------------------------------------

  /**
   * Attaches a file to an item.
   *
   * @param tenant whose
   * @param itemId what to attach it to
   * @param primary whether it is the image lists show
   * @param role what it is for
   * @param seed what makes this picture different from the others
   * @return the media object's id
   * @throws Exception when the upload fails
   */
  private UUID attach(Tenant tenant, UUID itemId, boolean primary, String role, int seed)
      throws Exception {
    return inOwn(
        tenant,
        () -> {
          try (var bytes = new java.io.ByteArrayInputStream(jpeg(seed))) {
            return media.upload(bytes, "ITEM", itemId, primary, role, tenant.userId()).id();
          } catch (java.io.IOException unreadable) {
            throw new IllegalStateException(unreadable);
          }
        });
  }

  /**
   * Marks every uploaded file clean.
   *
   * <p>The scan runs in the worker and this test is not about it; a report showing a file the
   * scanner has not cleared would be the failure {@code AttachedEvidence} is written to prevent, so
   * the fixture makes them clean rather than the report ignoring the state.
   *
   * @param tenant whose files
   */
  private void cleared(Tenant tenant) {
    inOwn(
        tenant,
        () ->
            jdbc.sql("update media.media_object set scan_state = 'CLEAN' where deleted_at is null")
                .update());
  }

  /**
   * A small JPEG, different for every seed.
   *
   * @param seed what makes it different, so that two uploads are two files
   * @return its bytes
   */
  private static byte[] jpeg(int seed) {
    // Visibly different rather than a pixel wider: the stand-in for libvips
    // derives from the source's length and one of its bytes, and three JPEGs
    // differing only in width can encode to the same length -- which makes one
    // content address, one media object, and one attachment where the test
    // meant three.
    java.awt.image.BufferedImage image =
        new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB);
    java.awt.Graphics2D pen = image.createGraphics();
    pen.setColor(new java.awt.Color(seed * 60 % 255, 255 - seed * 40 % 255, seed * 90 % 255));
    pen.fillRect(0, 0, 64, 64);
    pen.setColor(java.awt.Color.WHITE);
    pen.fillRect(seed * 5, seed * 7, 20, 20);
    pen.dispose();
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try {
      javax.imageio.ImageIO.write(image, "jpeg", out);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException(impossible);
    }
    return out.toByteArray();
  }

  private UUID anItem(Tenant tenant, String name, UUID where, Money replacement, int quantity) {
    return inOwn(
        tenant,
        () ->
            items
                .create(
                    new ItemService.CreateItemCommand(
                        null,
                        builtinType(tenant.tenantId()),
                        name,
                        null,
                        ItemKind.PHYSICAL,
                        where,
                        BigDecimal.valueOf(quantity),
                        null,
                        "{}",
                        null,
                        null,
                        new Valuation(
                            null,
                            null,
                            null,
                            null,
                            false,
                            replacement,
                            replacement == null ? null : LocalDate.of(2026, 1, 1),
                            replacement == null ? null : Valuation.Provenance.MANUAL,
                            null,
                            null)),
                    Optional.empty(),
                    tenant.userId())
                .item()
                .id());
  }

  private UUID aPlace(Tenant tenant, String name, UUID parent) {
    return inOwn(
        tenant,
        () ->
            locations
                .create(
                    new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                        null, anyCategory(tenant.tenantId()), parent,
                        name + " " + UUID.randomUUID(), null),
                    Optional.empty(),
                    tenant.userId())
                .id());
  }

  private UUID anyCategory(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = 'box'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private UUID builtinType(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.item_type where tenant_id = ? and key = 'general'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private <T> T inOwn(Tenant tenant, Supplier<T> body) {
    return TenantContext.callAs(tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Insured", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
