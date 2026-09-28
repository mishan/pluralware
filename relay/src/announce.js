// What friends are told about a switch. Mirrors
// shared/.../notify/SwitchAnnouncement.kt; test/announce.test.mjs mirrors its
// tests, so change both sides together.

/**
 * @param {string[]} memberUuids  the switch's members, in front order
 * @param {Record<string, string>} names  display labels for shared members only
 */
export function announce(memberUuids, names) {
  if (memberUuids.length === 0) return 'Switched out';
  const named = memberUuids.filter((uuid) => Object.hasOwn(names, uuid)).map((uuid) => names[uuid]);
  const anyHidden = named.length < memberUuids.length;
  if (named.length === 0) return 'Someone is fronting';
  if (anyHidden) return `${list([...named, 'someone else'])} are fronting`;
  if (named.length === 1) return `${named[0]} is fronting`;
  return `${list(named)} are fronting`;
}

/** "A", "A and B", "A, B, and C". */
function list(items) {
  if (items.length === 1) return items[0];
  if (items.length === 2) return `${items[0]} and ${items[1]}`;
  return `${items.slice(0, -1).join(', ')}, and ${items.at(-1)}`;
}
