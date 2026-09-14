/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.List;
import java.util.Set;

/**
 * Sends a rendered artifact to a printer (09 §9.2).
 *
 * <p>Download is in the core image and opens nothing — it writes to the {@link BlobStore} and hands
 * back a link. CUPS/IPP, Brother QL over the network, Zebra over TCP 9100 and Dymo are plugins,
 * because each of them opens a connection to a device the deployment does not contain.
 *
 * <p>Stage 2, written at stage 1 with the rest of the contract (REQ-PLG-001).
 */
public interface PrintTarget {

  /**
   * The printers this target can currently reach.
   *
   * <p>Asked when a person is choosing where to print and not on every job: discovery may take
   * seconds, and a printer list is worth caching for the length of a dialogue.
   *
   * @param context who is asking. A target may hold a different printer list per tenant; one that
   *     does not simply ignores this
   * @return the printers, possibly empty when none answered
   * @throws de.greluc.homeinv.plugin.api.PluginException when discovery itself failed, which is a
   *     different answer from finding nothing
   */
  List<Printer> printers(CallContext context);

  /**
   * Hands a job to a printer.
   *
   * <p>Returns as soon as the printer has accepted it, not when the paper comes out: a caller
   * waiting for the second would hold a request open for a minute. The job id is how it asks
   * afterwards.
   *
   * @param context who it is for
   * @param job what to print, and where
   * @return the accepted job
   * @throws de.greluc.homeinv.plugin.api.PluginException when the printer is unknown, refuses the
   *     media type, or cannot be reached
   */
  Accepted print(CallContext context, Job job);

  /**
   * How a job is getting on.
   *
   * @param context who is asking
   * @param jobId what {@link #print} returned
   * @return the state. {@link JobState#UNKNOWN} for a job the target no longer remembers, which is
   *     not an error: printers forget finished jobs, and so may an implementation
   */
  JobState state(CallContext context, String jobId);

  /**
   * One printer.
   *
   * @param printerId the stable id this target knows it by
   * @param name what a person sees
   * @param mediaTypes what it accepts, compared against a {@link LabelRenderer}'s outputs
   * @param location where it is, when the target knows — a CUPS location string, a room name. Empty
   *     otherwise
   */
  record Printer(String printerId, String name, Set<String> mediaTypes, String location) {}

  /**
   * One print job.
   *
   * @param printerId which printer
   * @param mediaType what the content is
   * @param content the artifact, as a {@link LabelRenderer} produced it
   * @param copies how many, at least one
   * @param jobName what to call it in the printer's queue, so that a person standing at the device
   *     can tell which job is theirs
   */
  record Job(String printerId, String mediaType, byte[] content, int copies, String jobName) {}

  /**
   * A job the printer took.
   *
   * @param jobId the id to ask about it with
   */
  record Accepted(String jobId) {}

  /** Where a job has got to. */
  enum JobState {
    /** Accepted and waiting. */
    QUEUED,
    /** On the device now. */
    PRINTING,
    /** Finished, as far as the target can tell. */
    DONE,
    /** Failed, and the target will not retry it. */
    FAILED,
    /** The target does not know this job, usually because it finished long enough ago to forget. */
    UNKNOWN
  }
}
