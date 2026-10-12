#import "ARMiniPlayerView.h"
#import "ARPlayer.h"
#import "ARTrack.h"
#import "ARTheme.h"
#import "ARIcons.h"
#import "ARArtwork.h"

@implementation ARMiniPlayerView {
    UIVisualEffectView *_blur;
    UIView *_progressTrack;
    UIView *_progress;
    UIImageView *_artwork;
    UILabel *_title;
    UILabel *_subtitle;
    UIButton *_playButton;
    UIButton *_nextButton;
    UIActivityIndicatorView *_spinner;
    UIView *_hairline;
    ARTrack *_shownTrack;
}

+ (CGFloat)height { return 58; }

- (instancetype)initWithFrame:(CGRect)frame {
    if ((self = [super initWithFrame:frame])) {
        _blur = [[UIVisualEffectView alloc] initWithEffect:[UIBlurEffect effectWithStyle:UIBlurEffectStyleDark]];
        [self addSubview:_blur];

        UIView *tint = [UIView new];
        tint.backgroundColor = [[ARTheme surface] colorWithAlphaComponent:0.55];
        tint.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
        tint.frame = _blur.contentView.bounds;
        [_blur.contentView addSubview:tint];

        _hairline = [UIView new];
        _hairline.backgroundColor = [ARTheme separator];
        [self addSubview:_hairline];

        _progressTrack = [UIView new];
        _progressTrack.backgroundColor = [UIColor colorWithWhite:1 alpha:0.06];
        [self addSubview:_progressTrack];
        _progress = [UIView new];
        _progress.backgroundColor = [ARTheme accent];
        [_progressTrack addSubview:_progress];

        _artwork = [UIImageView new];
        _artwork.contentMode = UIViewContentModeScaleAspectFill;
        _artwork.clipsToBounds = YES;
        _artwork.layer.cornerRadius = 5;
        [self addSubview:_artwork];

        _title = [UILabel new];
        _title.font = [ARTheme fontOfSize:15 weight:UIFontWeightSemibold];
        _title.textColor = [ARTheme textPrimary];
        [self addSubview:_title];

        _subtitle = [UILabel new];
        _subtitle.font = [ARTheme fontOfSize:13 weight:UIFontWeightRegular];
        _subtitle.textColor = [ARTheme textSecondary];
        [self addSubview:_subtitle];

        _playButton = [UIButton buttonWithType:UIButtonTypeSystem];
        _playButton.tintColor = [ARTheme textPrimary];
        [_playButton addTarget:[ARPlayer shared] action:@selector(togglePlayPause) forControlEvents:UIControlEventTouchUpInside];
        [self addSubview:_playButton];

        _spinner = [[UIActivityIndicatorView alloc] initWithActivityIndicatorStyle:UIActivityIndicatorViewStyleWhite];
        _spinner.userInteractionEnabled = NO;
        [self addSubview:_spinner];

        _nextButton = [UIButton buttonWithType:UIButtonTypeSystem];
        _nextButton.tintColor = [ARTheme textPrimary];
        [_nextButton setImage:[ARIcons icon:ARIconNext size:22] forState:UIControlStateNormal];
        [_nextButton addTarget:[ARPlayer shared] action:@selector(next) forControlEvents:UIControlEventTouchUpInside];
        [self addSubview:_nextButton];

        [self addGestureRecognizer:[[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(tapped)]];
        UISwipeGestureRecognizer *swipe = [[UISwipeGestureRecognizer alloc] initWithTarget:self action:@selector(tapped)];
        swipe.direction = UISwipeGestureRecognizerDirectionUp;
        [self addGestureRecognizer:swipe];

        NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
        [nc addObserver:self selector:@selector(update) name:ARPlayerDidChangeNotification object:nil];
        [nc addObserver:self selector:@selector(updateProgress) name:ARPlayerTimeNotification object:nil];
        [self update];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

- (void)tapped {
    if (self.onOpen) self.onOpen();
}

- (void)layoutSubviews {
    [super layoutSubviews];
    CGRect b = self.bounds;
    _blur.frame = b;
    _hairline.frame = CGRectMake(0, 0, b.size.width, 1 / [UIScreen mainScreen].scale);
    _progressTrack.frame = CGRectMake(0, 0, b.size.width, 2);
    _artwork.frame = CGRectMake(12, (b.size.height - 42) / 2 + 1, 42, 42);
    _nextButton.frame = CGRectMake(b.size.width - 50, 0, 44, b.size.height);
    _playButton.frame = CGRectMake(b.size.width - 94, 0, 44, b.size.height);
    _spinner.center = _playButton.center;
    CGFloat x = 66, w = b.size.width - 100 - x;
    _title.frame = CGRectMake(x, b.size.height / 2 - 18, w, 19);
    _subtitle.frame = CGRectMake(x, b.size.height / 2 + 2, w, 17);
    [self updateProgress];
}

- (void)update {
    ARPlayer *player = [ARPlayer shared];
    ARTrack *track = player.currentTrack;
    _title.text = track.title;
    if (player.errorMessage) {
        _subtitle.text = player.errorMessage;
        _subtitle.textColor = [ARTheme accent];
    } else {
        _subtitle.text = track.subtitle;
        _subtitle.textColor = [ARTheme textSecondary];
    }
    BOOL buffering = player.buffering;
    [_playButton setImage:buffering ? nil : [ARIcons icon:player.playing ? ARIconPause : ARIconPlay size:24] forState:UIControlStateNormal];
    if (buffering) [_spinner startAnimating];
    else [_spinner stopAnimating];

    if (track != _shownTrack) {
        _shownTrack = track;
        _artwork.image = [ARArtwork cachedArtworkForTrack:track size:48] ?: [ARTheme placeholderArtworkOfSize:42];
        [ARArtwork loadArtworkForTrack:track size:48 completion:^(UIImage *image) {
            if (image && self->_shownTrack == track) self->_artwork.image = image;
        }];
    }
    [self updateProgress];
}

- (void)updateProgress {
    ARPlayer *player = [ARPlayer shared];
    NSTimeInterval d = player.duration;
    CGFloat fraction = d > 0 ? MIN(1, MAX(0, player.currentTime / d)) : 0;
    _progress.frame = CGRectMake(0, 0, round(_progressTrack.bounds.size.width * fraction), 2);
}

@end
