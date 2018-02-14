/**
  * Wire
  * Copyright (C) 2018 Wire Swiss GmbH
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
package com.waz.zclient.usersearch

import android.Manifest.permission.READ_CONTACTS
import android.content.{DialogInterface, Intent}
import android.net.Uri
import android.os.Bundle
import android.support.design.widget.TabLayout
import android.support.design.widget.TabLayout.OnTabSelectedListener
import android.support.v7.app.AlertDialog
import android.support.v7.widget.{LinearLayoutManager, RecyclerView, Toolbar}
import android.view._
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.TextView.OnEditorActionListener
import android.widget._
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.content.UserPreferences
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.permissions.PermissionsService
import com.waz.service.ContactResult.ContactMethod
import com.waz.service.{ContactResult, NetworkModeService, ZMessaging}
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.events.{Signal, Subscription}
import com.waz.utils.returning
import com.waz.utils.wrappers.AndroidURIUtil
import com.waz.zclient._
import com.waz.zclient.common.controllers._
import com.waz.zclient.common.controllers.global.{AccentColorController, KeyboardController}
import com.waz.zclient.common.views.{FlatWireButton, PickableElement}
import com.waz.zclient.controllers.globallayout.IGlobalLayoutController
import com.waz.zclient.controllers.navigation.{INavigationController, Page}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.conversation.creation.{NewConversationController, NewConversationFragment}
import com.waz.zclient.conversationlist.ConversationListController
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.integrations.{IntegrationDetailsController, IntegrationDetailsFragment}
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.pages.main.participants.dialog.DialogLaunchMode
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.usersearch.adapters.PickUsersAdapter
import com.waz.zclient.usersearch.views.{ContactRowView, SearchEditText}
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{IntentUtils, RichView, StringUtils, UiStorage, UserSignal}
import com.waz.zclient.views._

import scala.concurrent.Future
import scala.concurrent.duration._

class PickUserFragment extends BaseFragment[PickUserFragment.Container]
  with FragmentHelper
  with OnBackPressedListener
  with SearchResultOnItemTouchListener.Callback
  with PickUsersAdapter.Callback {

  import PickUserFragment._
  import Threading.Implicits.Background

  private implicit lazy val uiStorage = inject[UiStorage]
  private implicit def context = getContext

  private lazy val zms                    = inject[Signal[ZMessaging]]
  private lazy val self                   = zms.flatMap(z => UserSignal(z.selfUserId))
  private lazy val userAccountsController = inject[UserAccountsController]
  private lazy val accentColor            = inject[AccentColorController].accentColor.map(_.getColor())
  private lazy val conversationController = inject[ConversationController]
  private lazy val browser                = inject[BrowserController]
  private lazy val integrationsController = inject[IntegrationsController]
  private lazy val convListController     = inject[ConversationListController]
  private lazy val keyboard               = inject[KeyboardController]

  private lazy val pickUserController     = inject[IPickUserController]
  private lazy val convScreenController   = inject[IConversationScreenController]
  private lazy val navigationController   = inject[INavigationController]

  private lazy val shareContactsPref     = zms.map(_.userPrefs.preference(UserPreferences.ShareContacts))
  private lazy val showShareContactsPref = zms.map(_.userPrefs.preference(UserPreferences.ShowShareContacts))

  private lazy val searchResultAdapter: PickUsersAdapter = new PickUsersAdapter(
    new SearchResultOnItemTouchListener(getActivity, this),
    this,
    searchUserController,
    integrationsController,
    darkTheme = true
  )
  private lazy val searchUserController = new SearchUserController()

  private lazy val searchResultRecyclerView = view[RecyclerView](R.id.rv__pickuser__header_list_view)
  private lazy val startUiToolbar           = view[Toolbar](R.id.pickuser_toolbar)
  private lazy val inviteButton = returning(view[FlatWireButton](R.id.invite_button)) { vh =>
    (for {
      kb  <- keyboard.keyboardVisibility
      sel <- searchUserController.selectedUsers
    } yield if (kb || sel.nonEmpty || isTeamAccount) View.GONE else View.VISIBLE)
      .onUi(vis => vh.foreach(_.setVisibility(vis)))
  }

  private lazy val searchBox = returning(view[SearchEditText](R.id.sbv__search_box)) { vh =>
    accentColor.onUi(color => vh.foreach(_.setCursorColor(color)))
  }

  private lazy val toolbarTitle = returning(view[TypefaceTextView](R.id.pickuser_title)) { vh =>
    (if (isPrivateAccount) userAccountsController.currentUser.map(_.map(_.name))
    else zms.flatMap(_.teams.selfTeam.map(_.map(_.name))))
      .map(_.getOrElse(""))
      .onUi(t => vh.foreach(_.setText(t)))
  }

  private lazy val errorMessageView = returning(view[TypefaceTextView](R.id.pickuser__error_text)) { vh =>
    (for {
      integrationTab <- searchResultAdapter.peopleOrServices
      hasSearch      <- integrationsController.searchQuery.map(_.nonEmpty)
      hasResults     <- integrationsController.searchIntegrations.map(_.forall(_.nonEmpty))
    } yield integrationTab && hasSearch && !hasResults).onUi { show =>
      vh.foreach(_.setVisibility(if (show) View.VISIBLE else View.GONE))
    }
  }

  private lazy val emptyListButton = returning(view[RelativeLayout](R.id.empty_list_button)){ v =>
    (for {
      zms         <- zms
      permissions <- userAccountsController.permissions.orElse(Signal.const(Set.empty[AccountData.Permission]))
      members     <- zms.teams.searchTeamMembers().orElse(Signal.const(Set.empty[UserData]))
      searching   <- searchUserController.filter.map(_.nonEmpty)
    } yield
      if (zms.teamId.nonEmpty && permissions(AccountData.Permission.AddTeamMember) && !members.exists(_.id != zms.selfUserId) && !searching)
        View.VISIBLE
      else
        View.GONE)
      .onUi(v.setVisibility(_))
  }

  private val searchBoxViewCallback = new SearchEditText.Callback {
    override def onRemovedTokenSpan(element: PickableElement): Unit = {}

    override def onFocusChange(hasFocus: Boolean): Unit = {}

    override def onClearButton(): Unit = closeStartUI()

    override def afterTextChanged(s: String): Unit = {
      searchBox.foreach { v =>
        val filter = v.getSearchFilter
        searchUserController.filter ! filter
        integrationsController.searchQuery ! filter
        if (filter.isEmpty) {
          setConversationQuickMenuVisible(false)
        }
      }
    }
  }

  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation = {
    if (nextAnim == 0 || getContainer == null)
      super.onCreateAnimation(transit, enter, nextAnim)
    else if (pickUserController.isHideWithoutAnimations)
      new DefaultPageTransitionAnimation(0, getOrientationIndependentDisplayHeight(getActivity), enter, 0, 0, 1f)
    else if (enter)
      new DefaultPageTransitionAnimation(0,
        getDimenPx(R.dimen.open_new_conversation__thread_list__max_top_distance),
        enter,
        getInt(R.integer.framework_animation_duration_long),
        getInt(R.integer.framework_animation_duration_medium),
        1f)
    else
      new DefaultPageTransitionAnimation(
        0,
        getDimenPx(R.dimen.open_new_conversation__thread_list__max_top_distance),
        enter,
        getInt(R.integer.framework_animation_duration_medium),
        0,
        1f)
  }

  override def onCreateView(inflater: LayoutInflater, viewContainer: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_pick_user, viewContainer, false)


  private var containerSub = Option.empty[Subscription]//TODO remove subscription...
  override def onViewCreated(rootView: View, savedInstanceState: Bundle): Unit = {

    searchResultRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity))
    searchResultRecyclerView.setAdapter(searchResultAdapter)
    searchResultRecyclerView.addOnItemTouchListener(new SearchResultOnItemTouchListener(getActivity, this))

    searchBox.setCallback(searchBoxViewCallback)

    inviteButton.setText(R.string.pref_invite_title)
    inviteButton.setGlyph(R.string.glyph__invite)

    emptyListButton.foreach(_.onClick(browser.openUrl(AndroidURIUtil.parse(getString(R.string.pick_user_manage_team_url)))))
    errorMessageView
    toolbarTitle

    inviteButton.setVisibility(if (isPrivateAccount) View.VISIBLE else View.GONE)
    // Use constant style for left side start ui
    startUiToolbar.setVisibility(View.VISIBLE)
    searchBox.applyDarkTheme(true)
    startUiToolbar.inflateMenu(R.menu.toolbar_close_white)
    startUiToolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
      override def onMenuItemClick(item: MenuItem): Boolean = {
        item.getItemId match {
          case R.id.close =>
            closeStartUI()
        }
        false
      }
    })

    searchBox.setOnEditorActionListener(new OnEditorActionListener {
      override def onEditorAction(v: TextView, actionId: Int, event: KeyEvent): Boolean =
        if (actionId == EditorInfo.IME_ACTION_SEARCH) keyboard.hideKeyboardIfVisible() else false
    })

    val tabs = findById[TabLayout](rootView, R.id.pick_user_tabs)
    searchResultAdapter.peopleOrServices.head.map {
      case false => 0
      case true  => 1
    }.foreach(tabs.getTabAt(_).select())

    tabs.addOnTabSelectedListener(new OnTabSelectedListener {
      override def onTabSelected(tab: TabLayout.Tab): Unit = {
        tab.getPosition match {
          case 0 => searchResultAdapter.peopleOrServices ! false
          case 1 => searchResultAdapter.peopleOrServices ! true
        }
        searchBox.removeAllElements()
      }
      override def onTabUnselected(tab: TabLayout.Tab): Unit = {}
      override def onTabReselected(tab: TabLayout.Tab): Unit = {}
    })

    zms.head
      .map(_.teamId.nonEmpty && internalVersion)
      .map(show => tabs.setVisibility(if (show) View.VISIBLE else View.GONE))(Threading.Ui)

    searchUserController.filter ! ""
    integrationsController.searchQuery ! ""

    containerSub = Some((for {
      kb <- keyboard.keyboardVisibility
      ac <- accentColor
      filterEmpty = !searchBox.flatMap(v => Option(v.getSearchFilter).map(_.isEmpty)).getOrElse(true)
    } yield if (kb || filterEmpty) getColor(R.color.people_picker__loading__color) else ac)
      .onUi(getContainer.getLoadingViewIndicator.setColor))
  }

  override def onStart(): Unit = {
    super.onStart()
    if (isPrivateAccount) showShareContactsDialog()
  }

  override def onResume(): Unit = {
    super.onResume()
    inviteButton.foreach(_.onClick(sendGenericInvite(false)))

    CancellableFuture.delay(getInt(R.integer.people_picker__keyboard__show_delay).millis).map { _ =>
      convListController.establishedConversations.head.map(_.size > PickUserFragment.SHOW_KEYBOARD_THRESHOLD && isTeamAccount).map {
        case true =>
          searchBox.foreach { v =>
            v.setFocus()
            keyboard.showKeyboardIfHidden()
          }
        case _ =>
      } (Threading.Ui)
    }
  }

  override def onPause(): Unit = {
    inviteButton.foreach(_.setOnClickListener(null))
    super.onPause()
  }

  override def onStop(): Unit = {
    //getContainer.getLoadingViewIndicator.hide()
    super.onStop()
  }


  override def onDestroyView() = {
    containerSub.foreach(_.destroy())
    containerSub = None
    super.onDestroyView()
  }

  override def onBackPressed: Boolean =
    keyboard.hideKeyboardIfVisible() || {
      if (pickUserController.isShowingUserProfile) {
        pickUserController.hideUserProfile()
        true
      } else false
    }

  override def onUserClicked(userId: UserId, anchorView: View): Unit = {
    zms.head.flatMap { z =>
      z.usersStorage.get(userId).map {
        case Some(user) =>
          import ConnectionStatus._
          keyboard.hideKeyboardIfVisible()
          if (z.teamId.isDefined || user.connection == Accepted)
            userAccountsController.getConversation(Set(userId)).map(_.id).map { convId =>
              conversationController.selectConv(convId, ConversationChangeRequester.START_CONVERSATION)
            }
          else if (!anchorView.isInstanceOf[ContactRowView] || (user.connection != ConnectionStatus.Unconnected)) {
            Future { user.connection match {
              case PendingFromUser | Blocked | Ignored | Cancelled | Unconnected =>
                convScreenController.setPopoverLaunchedMode(DialogLaunchMode.SEARCH)
                pickUserController.showUserProfile(userId, anchorView)
              case ConnectionStatus.PendingFromOther =>
                getContainer.showIncomingPendingConnectRequest(ConvId(userId.str))
              case _ =>
            }} (Threading.Ui)
          }
        case _ =>
      }
    }
  }

  override def onConversationClicked(conversationData: ConversationData): Unit = {
    keyboard.hideKeyboardIfVisible()
    verbose(s"onConversationClicked(${conversationData.id})")
    conversationController.selectConv(Some(conversationData.id), ConversationChangeRequester.START_CONVERSATION)
  }

  override def onCreateConvClicked(): Unit = {
    keyboard.hideKeyboardIfVisible()
    inject[NewConversationController].setCreateConversation()
    getFragmentManager.beginTransaction
      .setCustomAnimations(
        R.anim.slide_in_from_bottom_pick_user,
        R.anim.open_new_conversation__thread_list_out,
        R.anim.open_new_conversation__thread_list_in,
        R.anim.slide_out_to_bottom_pick_user)
      .replace(R.id.fl__conversation_list_main, new NewConversationFragment, NewConversationFragment.Tag)
      .addToBackStack(NewConversationFragment.Tag)
      .commit()

    //inject[INavigationController].setLeftPage(Page.CREATE_CONV, PickUserFragment.TAG)
  }

  override def onContactListContactClicked(contactDetails: ContactResult): Unit = {
    if (inject[NetworkModeService].isOnlineMode) {
      contactDetails.contactMethods.toList match {
        case method :: Nil if method.getType == ContactMethod.Phone =>
          sendSMSInvite(method)
        case method :: Nil if method.getType == ContactMethod.Email =>
          sendEmailInvite(method)
        case methods if methods.nonEmpty =>
          val itemNames: Array[CharSequence] = methods.map(_.stringRepresentation).toArray

          var dialog: AlertDialog = null
          val builder: AlertDialog.Builder = new AlertDialog.Builder(getActivity)
          builder.setTitle(getResources.getString(R.string.people_picker__contact_list__invite_dialog__title))
            .setPositiveButton(getResources.getText(R.string.confirmation_menu__confirm_done), new DialogInterface.OnClickListener() {
            def onClick(dialogInterface: DialogInterface, i: Int): Unit = {
              val lv: ListView = dialog.getListView
              val selected: Int = lv.getCheckedItemPosition

              if (contactDetails.contactMethods.isDefinedAt(selected)) {
                val selectedContactMethod = contactDetails.contactMethods(selected)
                if (selectedContactMethod.getType == ContactMethod.Phone) {
                  sendSMSInvite(selectedContactMethod)
                } else {
                  sendEmailInvite(selectedContactMethod)
                }
              }
            }
          }).setNegativeButton(getResources.getText(R.string.confirmation_menu__cancel), new DialogInterface.OnClickListener() {
            def onClick(dialogInterface: DialogInterface, i: Int): Unit = {
              dialogInterface.cancel()
            }
          }).setSingleChoiceItems(itemNames, PickUserFragment.DEFAULT_SELECTED_INVITE_METHOD, null)
          dialog = builder.create
          dialog.show()
        case _ =>
      }
    }
  }

  private def sendEmailInvite(contactMethod: ContactMethod) = {
    zms.head.flatMap(_.invitations.invite(contactMethod.contact.id, contactMethod.method, contactMethod.contact.name, " ", None))
    showToast(R.string.people_picker__invite__sent_feedback)
  }

  private def sendSMSInvite(contactMethod: ContactMethod): Unit = {
    self.head.map { self =>
      val smsBody = getString(R.string.people_picker__invite__share_text__body, self.handle.map(_.string).fold(self.getDisplayName)(StringUtils.formatHandle))
      val intent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("sms", contactMethod.stringRepresentation, ""))
      intent.putExtra("sms_body", smsBody)
      startActivity(intent)
    } (Threading.Ui)
  }

  private def getCurrentPickerDestination: IPickUserController.Destination =
    getContainer.getCurrentPickerDestination

  private def sendGenericInvite(fromSearch: Boolean): Unit =
    self.head.map { self =>
      val sharingIntent = IntentUtils.getInviteIntent(
        getString(R.string.people_picker__invite__share_text__header, self.getDisplayName),
        getString(R.string.people_picker__invite__share_text__body, StringUtils.formatHandle(self.handle.map(_.string).getOrElse(""))))
      startActivity(Intent.createChooser(sharingIntent, getString(R.string.people_picker__invite__share_details_dialog)))
    }(Threading.Ui)

  private def setConversationQuickMenuVisible(show: Boolean): Unit = {
    val visible = show || searchUserController.selectedUsers.currentValue.exists(_.nonEmpty) || keyboard.isVisible || isTeamAccount
    inviteButton.foreach(_.setVisibility(if (visible) View.GONE else View.VISIBLE))
  }

  private def isPrivateAccount: Boolean = !isTeamAccount

  private def isTeamAccount: Boolean = userAccountsController.isTeamAccount

  private def closeStartUI(): Unit = {
    keyboard.hideKeyboardIfVisible()
    searchUserController.filter ! ""
    integrationsController.searchQuery ! ""
    searchResultAdapter.peopleOrServices ! false
    pickUserController.hidePickUser(getCurrentPickerDestination)
  }

  // XXX Only show contact sharing dialogs for PERSONAL START UI
  private def showShareContactsDialog(): Unit = {
    (for {
      false <- shareContactsPref.head.flatMap(_.apply())
      true  <- showShareContactsPref.head.flatMap(_.apply())
    } yield {}).map { _ =>
      val checkBoxView= View.inflate(getContext, R.layout.dialog_checkbox, null)
      val checkBox = checkBoxView.findViewById(R.id.checkbox).asInstanceOf[CheckBox]
      var checked = false

      checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
        def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit =
          checked = isChecked
      })
      checkBox.setText(R.string.people_picker__share_contacts__nevvah)

      new AlertDialog.Builder(getContext)
        .setTitle(R.string.people_picker__share_contacts__title)
        .setMessage(R.string.people_picker__share_contacts__message)
        .setView(checkBoxView)
        .setPositiveButton(R.string.people_picker__share_contacts__yay,
          new DialogInterface.OnClickListener() {
            def onClick(dialog: DialogInterface, which: Int): Unit =
              inject[PermissionsService].requestAllPermissions(Set(READ_CONTACTS)).map { granted =>
                shareContactsPref.head.flatMap(_ := granted)
                if (!granted && !shouldShowRequestPermissionRationale(READ_CONTACTS)) showShareContactsPref.head.flatMap(_ := false)
              }(Threading.Ui)
          })
        .setNegativeButton(R.string.people_picker__share_contacts__nah,
          new DialogInterface.OnClickListener() {
            def onClick(dialog: DialogInterface, which: Int): Unit =
              if (checked) showShareContactsPref.head.flatMap(_ := false)
          }).create
        .show()
    }(Threading.Ui)
  }

  override def onIntegrationClicked(data: IntegrationData): Unit = {
    keyboard.hideKeyboardIfVisible()
    verbose(s"onIntegrationClicked(${data.id})")

    val detailsController = inject[IntegrationDetailsController]
    detailsController.setPicking()
    import IntegrationDetailsFragment._
    getFragmentManager.beginTransaction
      .setCustomAnimations(
        R.anim.slide_in_from_bottom_pick_user,
        R.anim.open_new_conversation__thread_list_out,
        R.anim.open_new_conversation__thread_list_in,
        R.anim.slide_out_to_bottom_pick_user)
      .replace(R.id.fl__conversation_list_main, newInstance(data.provider, data.id), Tag)
      .addToBackStack(Tag)
      .commit()

    navigationController.setLeftPage(Page.INTEGRATION_DETAILS, PickUserFragment.TAG)
  }
}

object PickUserFragment {
  val TAG: String = classOf[PickUserFragment].getName
  private val DEFAULT_SELECTED_INVITE_METHOD: Int = 0
  private val SHOW_KEYBOARD_THRESHOLD: Int = 10

  val internalVersion = BuildConfig.APPLICATION_ID match {
    case "com.wire.internal" | "com.waz.zclient.dev" | "com.wire.x" | "com.wire.qa" => true
    case _ => false
  }

  def newInstance(): PickUserFragment =
    new PickUserFragment

  trait Container {
    def showIncomingPendingConnectRequest(conv: ConvId): Unit

    def getLoadingViewIndicator: LoadingIndicatorView

    def getCurrentPickerDestination: IPickUserController.Destination
  }

}
