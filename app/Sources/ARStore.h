#import <Foundation/Foundation.h>

@class ARTrack;

extern NSString *const ARStoreDidChangeNotification;
/// Posted when Saved changes on this iPod, so it can be sent to the website.
extern NSString *const ARStoreSavedChangedLocallyNotification;

/// Saved and recently played YouTube tracks, kept in NSUserDefaults.
@interface ARStore : NSObject

+ (instancetype)shared;

@property (nonatomic, readonly) NSArray<ARTrack *> *savedTracks;
@property (nonatomic, readonly) NSArray<ARTrack *> *recentTracks;

- (BOOL)isSaved:(ARTrack *)track;
- (void)toggleSaved:(ARTrack *)track;
- (void)addRecent:(ARTrack *)track;
- (void)clearRecent;
/// Saves new metadata for a track wherever it appears in Saved and Recently Played.
- (void)updateTrack:(ARTrack *)track;

/// When Saved last changed (seconds since 1970), to decide which side of a sync is newer.
@property (nonatomic, readonly) NSTimeInterval savedUpdated;
/// Replaces Saved with the website's list.
- (void)replaceSaved:(NSArray<NSDictionary *> *)saved updated:(NSTimeInterval)updated;

@end
