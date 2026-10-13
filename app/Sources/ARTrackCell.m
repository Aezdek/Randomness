#import "ARTrackCell.h"
#import "ARTrack.h"
#import "ARTheme.h"
#import "ARIcons.h"
#import "ARArtwork.h"

@implementation AREqualizerView {
    NSArray<CALayer *> *_bars;
}

- (instancetype)initWithFrame:(CGRect)frame {
    if ((self = [super initWithFrame:frame])) {
        self.userInteractionEnabled = NO;
        NSMutableArray *bars = [NSMutableArray array];
        for (int i = 0; i < 3; i++) {
            CALayer *bar = [CALayer layer];
            bar.backgroundColor = [UIColor whiteColor].CGColor;
            bar.cornerRadius = 1;
            bar.anchorPoint = CGPointMake(0.5, 1);
            [self.layer addSublayer:bar];
            [bars addObject:bar];
        }
        _bars = bars;
    }
    return self;
}

- (CGSize)sizeThatFits:(CGSize)size { return CGSizeMake(16, 14); }

- (void)layoutSubviews {
    [super layoutSubviews];
    CGFloat w = 3, gap = 3, h = self.bounds.size.height;
    CGFloat x = (self.bounds.size.width - (w * 3 + gap * 2)) / 2;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    for (NSUInteger i = 0; i < _bars.count; i++) {
        _bars[i].bounds = CGRectMake(0, 0, w, h);
        _bars[i].position = CGPointMake(x + w / 2 + i * (w + gap), h);
    }
    [CATransaction commit];
    [self applyAnimation];
}

- (void)setAnimating:(BOOL)animating {
    _animating = animating;
    [self applyAnimation];
}

- (void)didMoveToWindow {
    [super didMoveToWindow];
    [self applyAnimation]; // animations are dropped when a view leaves the window
}

- (void)applyAnimation {
    CGFloat rest[3] = {0.45, 0.85, 0.6};
    double durations[3] = {0.42, 0.33, 0.51};
    for (NSUInteger i = 0; i < _bars.count; i++) {
        CALayer *bar = _bars[i];
        if (self.animating && self.window) {
            if ([bar animationForKey:@"eq"]) continue;
            CABasicAnimation *a = [CABasicAnimation animationWithKeyPath:@"transform.scale.y"];
            a.fromValue = @0.2;
            a.toValue = @1.0;
            a.duration = durations[i];
            a.autoreverses = YES;
            a.repeatCount = HUGE_VALF;
            a.timingFunction = [CAMediaTimingFunction functionWithName:kCAMediaTimingFunctionEaseInEaseOut];
            a.removedOnCompletion = NO;
            [bar addAnimation:a forKey:@"eq"];
        } else {
            [bar removeAnimationForKey:@"eq"];
            [CATransaction begin];
            [CATransaction setDisableActions:YES];
            bar.transform = CATransform3DMakeScale(1, rest[i], 1);
            [CATransaction commit];
        }
    }
}

@end

@implementation ARTrackCell {
    UIView *_dimView;
    AREqualizerView *_equalizer;
    UIButton *_moreButton;
    NSString *_artworkKey;
}

+ (CGFloat)height { return 64; }

- (instancetype)initWithStyle:(UITableViewCellStyle)style reuseIdentifier:(NSString *)reuseIdentifier {
    if ((self = [super initWithStyle:UITableViewCellStyleDefault reuseIdentifier:reuseIdentifier])) {
        self.backgroundColor = [ARTheme background];
        UIView *selected = [UIView new];
        selected.backgroundColor = [ARTheme surfaceHighlight];
        self.selectedBackgroundView = selected;
        _showsArtwork = YES;

        _artworkView = [UIImageView new];
        _artworkView.contentMode = UIViewContentModeScaleAspectFill;
        _artworkView.clipsToBounds = YES;
        _artworkView.layer.cornerRadius = [ARTheme artworkCornerRadius:6];
        _artworkView.backgroundColor = [ARTheme surface];
        [self.contentView addSubview:_artworkView];

        _dimView = [UIView new];
        _dimView.backgroundColor = [UIColor colorWithWhite:0 alpha:0.45];
        _dimView.hidden = YES;
        [_artworkView addSubview:_dimView];

        _equalizer = [AREqualizerView new];
        [_dimView addSubview:_equalizer];

        _titleLabel = [UILabel new];
        _titleLabel.font = [ARTheme fontOfSize:16 weight:UIFontWeightMedium];
        _titleLabel.textColor = [ARTheme textPrimary];
        [self.contentView addSubview:_titleLabel];

        _subtitleLabel = [UILabel new];
        _subtitleLabel.font = [ARTheme fontOfSize:13 weight:UIFontWeightRegular];
        _subtitleLabel.textColor = [ARTheme textSecondary];
        [self.contentView addSubview:_subtitleLabel];

        _detailLabel = [UILabel new];
        _detailLabel.font = [ARTheme digitsFontOfSize:13 weight:UIFontWeightRegular];
        _detailLabel.textColor = [ARTheme textSecondary];
        _detailLabel.textAlignment = NSTextAlignmentRight;
        [self.contentView addSubview:_detailLabel];

        _moreButton = [UIButton buttonWithType:UIButtonTypeSystem];
        [_moreButton setImage:[ARIcons icon:ARIconMore size:20] forState:UIControlStateNormal];
        _moreButton.tintColor = [ARTheme textSecondary];
        [_moreButton addTarget:self action:@selector(moreTapped) forControlEvents:UIControlEventTouchUpInside];
        [self.contentView addSubview:_moreButton];
    }
    return self;
}

- (void)moreTapped {
    if (self.onMore) self.onMore(self);
}

- (void)prepareForReuse {
    [super prepareForReuse];
    self.onMore = nil;
    _artworkKey = nil;
    self.showsArtwork = YES;
    self.contentView.alpha = 1;
    [self setCurrent:NO playing:NO];
}

- (void)layoutSubviews {
    [super layoutSubviews];
    CGRect b = self.contentView.bounds;
    CGFloat x = 16;
    _artworkView.hidden = !self.showsArtwork;
    if (self.showsArtwork) {
        _artworkView.frame = CGRectMake(x, (b.size.height - 48) / 2, 48, 48);
        _dimView.frame = _artworkView.bounds;
        _equalizer.frame = CGRectMake(14, 17, 20, 14);
        x += 48 + 12;
    }
    CGFloat right = b.size.width - 12;
    _moreButton.hidden = self.onMore == nil;
    if (self.onMore) {
        _moreButton.frame = CGRectMake(right - 36, (b.size.height - 44) / 2, 40, 44);
        right -= 40;
    }
    CGFloat detailWidth = 0;
    if (_detailLabel.text.length) {
        detailWidth = ceil([_detailLabel sizeThatFits:b.size].width);
        _detailLabel.frame = CGRectMake(right - detailWidth, 0, detailWidth, b.size.height);
        detailWidth += 10;
    } else {
        _detailLabel.frame = CGRectZero;
    }
    CGFloat textWidth = right - detailWidth - x;
    if (_subtitleLabel.text.length) {
        _titleLabel.frame = CGRectMake(x, b.size.height / 2 - 20, textWidth, 20);
        _subtitleLabel.frame = CGRectMake(x, b.size.height / 2 + 1, textWidth, 18);
    } else {
        _titleLabel.frame = CGRectMake(x, (b.size.height - 20) / 2, textWidth, 20);
        _subtitleLabel.frame = CGRectZero;
    }
}

- (void)configureWithTrack:(ARTrack *)track {
    self.titleLabel.text = track.title;
    self.subtitleLabel.text = track.subtitle;
    self.detailLabel.text = track.duration > 0 ? [ARTheme formatTime:track.duration] : nil;
    self.contentView.alpha = track.playable ? 1 : 0.35;
    [self loadArtworkForTrack:track];
    [self setNeedsLayout];
}

- (void)loadArtworkForTrack:(ARTrack *)track {
    NSString *key = [NSString stringWithFormat:@"%ld:%@", (long)track.source, track.identifier];
    _artworkKey = key;
    UIImage *cached = [ARArtwork cachedArtworkForTrack:track size:48];
    self.artworkView.image = cached ?: [ARTheme placeholderArtworkOfSize:48];
    if (cached) return;
    [ARArtwork loadArtworkForTrack:track size:48 completion:^(UIImage *image) {
        if (!image || ![self->_artworkKey isEqualToString:key]) return;
        [UIView transitionWithView:self.artworkView duration:0.2 options:UIViewAnimationOptionTransitionCrossDissolve animations:^{
            self.artworkView.image = image;
        } completion:nil];
    }];
}

- (void)setCurrent:(BOOL)current playing:(BOOL)playing {
    _dimView.hidden = !current;
    _equalizer.animating = current && playing;
    _titleLabel.textColor = current ? [ARTheme accent] : [ARTheme textPrimary];
}

@end
