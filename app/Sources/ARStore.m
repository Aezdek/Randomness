#import "ARStore.h"
#import "ARTrack.h"

NSString *const ARStoreDidChangeNotification = @"ARStoreDidChangeNotification";

static NSString *const ARSavedKey = @"ARSavedTracks";
static NSString *const ARRecentKey = @"ARRecentTracks";
static const NSUInteger ARRecentLimit = 30;

@implementation ARStore {
    NSMutableArray<ARTrack *> *_saved;
    NSMutableArray<ARTrack *> *_recent;
}

+ (instancetype)shared {
    static ARStore *store;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ store = [ARStore new]; });
    return store;
}

- (instancetype)init {
    if ((self = [super init])) {
        _saved = [self load:ARSavedKey];
        _recent = [self load:ARRecentKey];
    }
    return self;
}

- (NSMutableArray *)load:(NSString *)key {
    NSMutableArray *tracks = [NSMutableArray array];
    for (NSDictionary *d in [[NSUserDefaults standardUserDefaults] arrayForKey:key]) {
        if (![d isKindOfClass:[NSDictionary class]]) continue;
        ARTrack *t = [ARTrack trackWithDictionary:d];
        if (t) [tracks addObject:t];
    }
    return tracks;
}

- (void)save:(NSArray *)tracks key:(NSString *)key {
    [[NSUserDefaults standardUserDefaults] setObject:[tracks valueForKey:@"dictionaryRepresentation"] forKey:key];
    [[NSNotificationCenter defaultCenter] postNotificationName:ARStoreDidChangeNotification object:self];
}

- (NSArray<ARTrack *> *)savedTracks { return [_saved copy]; }
- (NSArray<ARTrack *> *)recentTracks { return [_recent copy]; }

- (NSUInteger)indexOf:(ARTrack *)track in:(NSArray *)list {
    return [list indexOfObjectPassingTest:^BOOL(ARTrack *t, NSUInteger i, BOOL *stop) { return [t isSameTrack:track]; }];
}

- (BOOL)isSaved:(ARTrack *)track {
    return track.source == ARTrackSourceYouTube && [self indexOf:track in:_saved] != NSNotFound;
}

- (void)toggleSaved:(ARTrack *)track {
    if (track.source != ARTrackSourceYouTube) return;
    NSUInteger i = [self indexOf:track in:_saved];
    if (i == NSNotFound) [_saved insertObject:track atIndex:0];
    else [_saved removeObjectAtIndex:i];
    [self save:_saved key:ARSavedKey];
}

- (void)addRecent:(ARTrack *)track {
    if (track.source != ARTrackSourceYouTube) return;
    NSUInteger i = [self indexOf:track in:_recent];
    if (i != NSNotFound) [_recent removeObjectAtIndex:i];
    [_recent insertObject:track atIndex:0];
    while (_recent.count > ARRecentLimit) [_recent removeLastObject];
    [self save:_recent key:ARRecentKey];
}

- (void)updateTrack:(ARTrack *)track {
    BOOL changed = NO;
    for (NSMutableArray *list in @[_saved, _recent]) {
        NSUInteger i = [self indexOf:track in:list];
        if (i == NSNotFound) continue;
        list[i] = track;
        changed = YES;
    }
    if (!changed) return;
    [[NSUserDefaults standardUserDefaults] setObject:[_saved valueForKey:@"dictionaryRepresentation"] forKey:ARSavedKey];
    [self save:_recent key:ARRecentKey];
}

- (void)clearRecent {
    [_recent removeAllObjects];
    [self save:_recent key:ARRecentKey];
}

@end
