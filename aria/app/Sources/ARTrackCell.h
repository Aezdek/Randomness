#import <UIKit/UIKit.h>

@class ARTrack;

/// Three bouncing bars that mark the playing row.
@interface AREqualizerView : UIView
@property (nonatomic, getter=isAnimating) BOOL animating;
@end

@interface ARTrackCell : UITableViewCell

@property (nonatomic, readonly) UIImageView *artworkView;
@property (nonatomic, readonly) UILabel *titleLabel;
@property (nonatomic, readonly) UILabel *subtitleLabel;
@property (nonatomic, readonly) UILabel *detailLabel;
/// Shows a "..." button on the right when set.
@property (nonatomic, copy) void (^onMore)(ARTrackCell *cell);
/// Set to NO to show a plain row without artwork.
@property (nonatomic) BOOL showsArtwork;

+ (CGFloat)height;
- (void)configureWithTrack:(ARTrack *)track;
/// Artwork for a row that isn't a track, such as an album.
- (void)loadArtworkForTrack:(ARTrack *)track;
- (void)setCurrent:(BOOL)current playing:(BOOL)playing;

@end
