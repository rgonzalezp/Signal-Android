package org.thoughtcrime.securesms.jobmanager;

import android.support.annotation.NonNull;

import java.util.Arrays;
import java.util.List;

class CompositeScheduler implements Scheduler {

  private final List<Scheduler> schedulers;

  CompositeScheduler(@NonNull Scheduler... schedulers) {
    this.schedulers = Arrays.asList(schedulers);
  }

  @Override
  public void schedule(long delay, @NonNull List<Constraint> constraints) {
    Scheduler scheduler = null;
    for (int i = 0; i < schedulers.size() ; i++) {
      scheduler = schedulers.get(i);
      scheduler.schedule(delay, constraints);
    }
  }
}
