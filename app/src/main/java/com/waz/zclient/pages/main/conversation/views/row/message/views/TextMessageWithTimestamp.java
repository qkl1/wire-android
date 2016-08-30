/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.pages.main.conversation.views.row.message.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.waz.api.Message;
import com.waz.zclient.R;
import com.waz.zclient.controllers.accentcolor.AccentColorObserver;
import com.waz.zclient.core.api.scala.ModelObserver;
import com.waz.zclient.pages.main.conversation.views.MessageViewsContainer;
import com.waz.zclient.ui.text.LinkTextView;
import com.waz.zclient.ui.text.TypefaceTextView;
import com.waz.zclient.utils.ViewUtils;
import com.waz.zclient.utils.ZTimeFormatter;
import org.threeten.bp.DateTimeUtils;
import org.threeten.bp.Instant;

public class TextMessageWithTimestamp extends LinearLayout implements AccentColorObserver {

    private final float textSizeRegular;
    private final float textSizeEmoji;

    private LinkTextView messageTextView;
    private TypefaceTextView timestampTextView;
    private MessageViewsContainer messageViewContainer;
    private boolean isLongClick;
    private final ModelObserver<Message> messageModelObserver = new ModelObserver<Message>() {
        @Override
        public void updated(Message message) {
            if (messageTextView == null ||
                timestampTextView == null ||
                messageViewContainer == null ||
                messageViewContainer.isTornDown()) {
                return;
            }

            resizeIfEmoji(message);

            String messageText;
            if (message.isDeleted()) {
                messageText = "";
            } else {
                messageText = message.getBody();
                messageText = messageText.replaceAll("\u2028", "\n");
            }

            if (message.getMessageType() == Message.Type.RECALLED) {
                messageTextView.setVisibility(GONE);
            } else {
                messageTextView.setVisibility(VISIBLE);
                messageTextView.setLinkTextColor(messageViewContainer.getControllerFactory().getAccentColorController().getColor());
                messageTextView.setTextLink(messageText);
            }

            String timestamp;
            Message.Status messageStatus = message.getMessageStatus();
            if (messageStatus == Message.Status.PENDING) {
                timestamp = getResources().getString(R.string.content_system_message_timestamp_pending);
            } else if (messageStatus == Message.Status.FAILED) {
                timestamp = getResources().getString(R.string.content_system_message_timestamp_failure);
            } else if (message.getMessageType() == Message.Type.RECALLED) {
                String time = ZTimeFormatter.getSingleMessageTimeAndDate(getContext(), DateTimeUtils.toDate(message.getEditTime()));
                timestamp = getContext().getString(R.string.content_system_message_timestamp_deleted, time);
            } else {
                Instant messageTime = message.isEdited() ?
                                      message.getEditTime() :
                                      message.getTime();
                timestamp = ZTimeFormatter.getSingleMessageTimeAndDate(getContext(), DateTimeUtils.toDate(messageTime));
            }

            timestampTextView.setTransformedText(timestamp);
            timestampTextView.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    collapseTimestamp();
                }
            });
        }
    };
    private int animationDuration;
    private Message message;

    public TextMessageWithTimestamp(Context context) {
        this(context, null);
    }

    public TextMessageWithTimestamp(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextMessageWithTimestamp(final Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        LayoutInflater.from(context).inflate(R.layout.row_conversation_text_with_timestamp, this, true);
        setOrientation(VERTICAL);

        messageTextView = ViewUtils.getView(this, R.id.ltv__row_conversation__message);
        timestampTextView = ViewUtils.getView(this, R.id.ttv__row_conversation__timestamp);
        animationDuration = getResources().getInteger(R.integer.content__message_timestamp__animation_duration);

        textSizeRegular = context.getResources().getDimensionPixelSize(R.dimen.wire__text_size__regular);
        textSizeEmoji = context.getResources().getDimensionPixelSize(R.dimen.wire__text_size__emoji);

        messageTextView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP && isLongClick) {
                    isLongClick = false;
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    isLongClick = false;
                }
                return v.onTouchEvent(event);
            }
        });

        messageTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTimestamp();
            }
        });

        messageTextView.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                isLongClick = true;
                messageViewContainer.onItemLongClick(message);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return true;
            }
        });
    }

    public void setMessage(final Message message) {
        messageModelObserver.setAndUpdate(message);
        if (message.getMessageType() == Message.Type.RECALLED) {
            messageTextView.setVisibility(GONE);
        } else {
            messageTextView.setVisibility(VISIBLE);
        }
        this.message = message;
//        if (messageViewContainer.getTimestampShownSet().contains(message.getId())) {
            //messageViewContainer.setExpandedView(this);
//            timestampTextView.setVisibility(VISIBLE);
//        } else {
            timestampTextView.setVisibility(GONE);
//        }
    }

    private void expandTimestamp() {
//        messageViewContainer.getTimestampShownSet().add(message.getId());
        timestampTextView.setVisibility(VISIBLE);

        View parent = (View) timestampTextView.getParent();
        final int widthSpec = MeasureSpec.makeMeasureSpec(parent.getMeasuredWidth()
                                                          - parent.getPaddingLeft()
                                                          - parent.getPaddingRight(),
                                                          MeasureSpec.AT_MOST);
        final int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        timestampTextView.measure(widthSpec, heightSpec);
        ValueAnimator animator = createHeightAnimator(timestampTextView, 0, timestampTextView.getMeasuredHeight());
        animator.start();
    }

    private void collapseTimestamp() {
//        messageViewContainer.getTimestampShownSet().remove(message.getId());
        int origHeight = timestampTextView.getHeight();

        ValueAnimator animator = createHeightAnimator(timestampTextView, origHeight, 0);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final Animator animator) {
                timestampTextView.setVisibility(View.GONE);
            }
        });
        animator.start();
    }

    public ValueAnimator createHeightAnimator(final View view, final int start, final int end) {
        ValueAnimator animator = ValueAnimator.ofInt(start, end);
        animator.setDuration(animationDuration);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(final ValueAnimator valueAnimator) {
                ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
                layoutParams.height = (Integer) valueAnimator.getAnimatedValue();
                view.setLayoutParams(layoutParams);
            }
        });
        return animator;
    }

    private boolean isClickInsideLink(MotionEvent event) {
        Object text = messageTextView.getText();
        if (text != null && text instanceof Spanned) {
            Spannable buffer = (Spannable) text;

            int x = (int) event.getX();
            int y = (int) event.getY();

            x -= messageTextView.getTotalPaddingLeft();
            y -= messageTextView.getTotalPaddingTop();

            x += messageTextView.getScrollX();
            y += messageTextView.getScrollY();

            Layout layout = messageTextView.getLayout();
            int line = layout.getLineForVertical(y);
            int off = layout.getOffsetForHorizontal(line, x);

            ClickableSpan[] link = buffer.getSpans(off, off, ClickableSpan.class);

            return link.length > 0;

        }
        return false;
    }

    public void setMessageViewsContainer(MessageViewsContainer messageViewContainer) {
        this.messageViewContainer = messageViewContainer;
        messageViewContainer.getControllerFactory().getAccentColorController().addAccentColorObserver(this);
    }

    public void toggleTimestamp() {
        timestampTextView.clearAnimation();
        if (timestampTextView.getVisibility() == GONE) {
            expandTimestamp();
        } else {
            collapseTimestamp();
        }
    }

    @Override
    public void onAccentColorHasChanged(Object sender, int color) {
        messageTextView.setLinkTextColor(color);
    }

    public void recycle() {
        if (!messageViewContainer.isTornDown()) {
            messageViewContainer.getControllerFactory().getAccentColorController().removeAccentColorObserver(this);
        }
        messageModelObserver.pauseListening();
    }

    private void resizeIfEmoji(Message message) {
        if (message.getMessageType() == Message.Type.TEXT_EMOJI_ONLY) {
            messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeEmoji);
        } else {
            messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeRegular);
        }
    }
}
