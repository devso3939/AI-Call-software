// Realtime event bus (Master Prompt §45).
// In-memory pub/sub for the single-process MVP. The Redis adapter interface is
// defined below so swapping to Redis later is a configuration change, not a rewrite.

export type Handler = (event: RealtimeEvent) => void;

export interface RealtimeEvent {
  type: string;
  callId?: string;
  workspaceId?: string;
  toUserId?: string;
  roomName?: string;
  timestamp: string;
  payload?: Record<string, unknown>;
}

export interface EventBus {
  publish(event: RealtimeEvent): void;
  subscribe(userId: string, handler: Handler): () => void;
  subscribeAll(handler: Handler): () => void;
}

class InMemoryEventBus implements EventBus {
  private perUser = new Map<string, Set<Handler>>();
  private globalHandlers = new Set<Handler>();

  publish(event: RealtimeEvent): void {
    const stamped = { ...event, timestamp: event.timestamp ?? new Date().toISOString() };
    if (event.toUserId) {
      const set = this.perUser.get(event.toUserId);
      if (set) for (const h of set) h(stamped);
    } else {
      for (const set of this.perUser.values()) for (const h of set) h(stamped);
    }
    for (const h of this.globalHandlers) h(stamped);
  }

  subscribe(userId: string, handler: Handler): () => void {
    let set = this.perUser.get(userId);
    if (!set) {
      set = new Set();
      this.perUser.set(userId, set);
    }
    set.add(handler);
    return () => set!.delete(handler);
  }

  subscribeAll(handler: Handler): () => void {
    this.globalHandlers.add(handler);
    return () => this.globalHandlers.delete(handler);
  }
}

// Future: class RedisEventBus implements EventBus { ... } via REDIS_URL.

let bus: EventBus | null = null;

export function getEventBus(): EventBus {
  if (!bus) bus = new InMemoryEventBus();
  return bus;
}
