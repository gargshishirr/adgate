import { useCallback, useEffect, useMemo, useState } from 'preact/hooks';

import { Simulation } from '../sim/simulation';
import { CampaignsPanel } from './Campaigns';
import { ChaosPanel } from './Chaos';
import { ComposerPanel } from './Composer';
import { ExplainDrawer } from './Explain';
import { Header, REPO_URL, TryThis } from './Header';
import { TrafficPanel, Transport } from './Traffic';

/** Re-render at most this often while only the clock is moving; user actions re-render at once. */
const FRAME_BUDGET_MILLIS = 50;

export function App() {
  const sim = useMemo(() => new Simulation(), []);
  const [, setVersion] = useState(0);
  const [explain, setExplain] = useState<{ requestId: string; adId?: string } | null>(null);

  useEffect(() => {
    let frame = 0;
    let last = performance.now();
    let lastRender = 0;
    const loop = (now: number) => {
      sim.tick(now - last);
      last = now;
      if (now - lastRender >= FRAME_BUDGET_MILLIS) {
        lastRender = now;
        setVersion(sim.version);
      }
      frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);
    const unsubscribe = sim.subscribe(() => setVersion(sim.version));
    return () => {
      cancelAnimationFrame(frame);
      unsubscribe();
    };
  }, [sim]);

  const openExplain = useCallback(
    (requestId: string, adId?: string) => {
      setExplain({ requestId, adId });
      sim.markExplained();
    },
    [sim],
  );
  const closeExplain = useCallback(() => setExplain(null), []);
  const entry = explain ? sim.find(explain.requestId) : undefined;

  return (
    <>
      <a class="skip-link" href="#playground">
        Skip to the playground
      </a>
      <div class="page">
        <Header />
        <main id="playground" tabIndex={-1}>
          <TryThis done={sim.milestones} />
          <Transport sim={sim} />
          <div class="layout">
            <div class="column column--a">
              <ComposerPanel sim={sim} onExplain={openExplain} />
            </div>
            <div class="column column--b">
              <TrafficPanel sim={sim} onExplain={openExplain} />
            </div>
            <div class="column column--c">
              <ChaosPanel sim={sim} />
              <CampaignsPanel sim={sim} />
            </div>
          </div>
        </main>
        <footer class="footer">
          <p>
            Only the decision engine is a port; Redis, Kafka, the spend aggregator, the config cache, the circuit breaker and
            the traffic are small simulations of the real services. Each simulated request stands for a slice of a busy ad
            server's traffic, so a whole day can play out in minutes.
          </p>
          <p>
            <a href={REPO_URL} target="_blank" rel="noopener noreferrer">
              AdGate on GitHub
            </a>{' '}
            · MIT licensed
          </p>
        </footer>
      </div>
      {explain && entry ? (
        <ExplainDrawer sim={sim} entry={entry} initialAdId={explain.adId} onClose={closeExplain} />
      ) : null}
    </>
  );
}
