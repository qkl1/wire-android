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
package com.waz.zclient.appentry.fragments

import android.content.Context
import android.graphics.Color
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.KITKAT
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.transition._
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{FrameLayout, ImageView, LinearLayout}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.model.{EmailAddress, PhoneNumber}
import com.waz.service.{AccountsService, ZMessaging}
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.utils.returning
import com.waz.zclient._
import com.waz.zclient.appentry.{AppEntryActivity, SSOFragment, SSOWebViewFragment}
import com.waz.zclient.appentry.DialogErrorMessage.{EmailError, PhoneError}
import com.waz.zclient.appentry.fragments.SignInFragment._
import com.waz.zclient.common.controllers.BrowserController
import com.waz.zclient.newreg.fragments.TabPages
import com.waz.zclient.newreg.fragments.country.{Country, CountryController}
import com.waz.zclient.newreg.views.PhoneConfirmationButton
import com.waz.zclient.pages.main.profile.validator.{EmailValidator, NameValidator, PasswordValidator}
import com.waz.zclient.pages.main.profile.views.GuidedEditText
import com.waz.zclient.tracking.{GlobalTrackingController, SignUpScreenEvent}
import com.waz.zclient.ui.text.{GlyphTextView, TypefaceEditText, TypefaceTextView}
import com.waz.zclient.ui.utils.{KeyboardUtils, TextViewUtils}
import com.waz.zclient.ui.views.tab.TabIndicatorLayout
import com.waz.zclient.ui.views.tab.TabIndicatorLayout.Callback
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils._

import scala.concurrent.Future

class SignInFragment extends SSOFragment
  with View.OnClickListener
  with CountryController.Observer {

  implicit def context: Context = getActivity

  private lazy val accountsService    = inject[AccountsService]
  private lazy val browserController  = inject[BrowserController]
  private lazy val tracking           = inject[GlobalTrackingController]

  private lazy val isAddingAccount = accountsService.zmsInstances.map(_.nonEmpty)

  private lazy val uiSignInState = {
    val sign = getStringArg(SignTypeArg) match {
      case Some(Login.str) => Login
      case _               => Register
    }

    val input = getStringArg(InputTypeArg) match {
      case Some(Email.str) => Email
      case _               => Phone
    }

    val onlyLogin = getBooleanArg(OnlyLoginArg)

    Signal(SignInMethod(sign, input, onlyLogin))
  }

  private val email    = Signal("")
  private val password = Signal("")
  private val name     = Signal("")
  private val phone    = Signal("")

  private lazy val countryController = activity.getCountryController //TODO rewrite && inject
  private lazy val phoneCountry = Signal[Country]()

  private lazy val nameValidator = new NameValidator()
  private lazy val emailValidator = EmailValidator.newInstance()
  private lazy val passwordValidator = PasswordValidator.instance(context)
  private lazy val legacyPasswordValidator = PasswordValidator.instanceLegacy(context)

  lazy val isValid: Signal[Boolean] = uiSignInState.flatMap {
    case SignInMethod(Login, Email, _) =>
      for {
        email    <- email
        password <- password
      } yield emailValidator.validate(email) && legacyPasswordValidator.validate(password)
    case SignInMethod(Register, Email, false) =>
      for {
        name     <- name
        email    <- email
        password <- password
      } yield nameValidator.validate(name) && emailValidator.validate(email) && passwordValidator.validate(password)
    case SignInMethod(_, Phone, false) =>
      phone.map(_.nonEmpty)
    case _ => Signal.empty[Boolean]
  }

  private lazy val container = view[FrameLayout](R.id.sign_in_container)
  private lazy val scenes = Array(
    R.layout.sign_in_email_scene,
    R.layout.sign_in_phone_scene,
    R.layout.sign_up_email_scene,
    R.layout.sign_up_phone_scene
  )

  private lazy val logo        = view[ImageView](R.id.iv__reg__logo)
  private lazy val phoneButton = view[TypefaceTextView](R.id.ttv__new_reg__sign_in__go_to__phone)
  private lazy val emailButton = view[TypefaceTextView](R.id.ttv__new_reg__sign_in__go_to__email)
  private lazy val tabSelector = view[TabIndicatorLayout](R.id.til__app_entry)
  private lazy val closeButton = view[GlyphTextView](R.id.close_button)

  def nameField = Option(findById[GuidedEditText](getView, R.id.get__sign_in__name))

  def emailField = Option(findById[GuidedEditText](getView, R.id.get__sign_in__email))
  def passwordField = Option(findById[GuidedEditText](getView, R.id.get__sign_in__password))

  def phoneField = Option(findById[TypefaceEditText](getView, R.id.et__reg__phone))
  def countryNameText = Option(findById[TypefaceTextView](R.id.ttv_new_reg__signup__phone__country_name))
  def countryCodeText = Option(findById[TypefaceTextView](R.id.tv__country_code))
  def countryButton = Option(findById[LinearLayout](R.id.ll__signup__country_code__button))

  def confirmationButton = Option(findById[PhoneConfirmationButton](R.id.pcb__signin__email))

  def termsOfService = Option(findById[TypefaceTextView](R.id.terms_of_service_text))

  def forgotPasswordButton = Option(findById[View](getView, R.id.ttv_signin_forgot_password))
  def companyLoginButton = Option(findById[View](getView, R.id.ttv_signin_sso))

  def setupViews(onlyLogin: Boolean): Unit = {

    tabSelector.foreach(_.setVisible(!onlyLogin))
    emailButton.foreach(_.setVisible(!onlyLogin))
    phoneButton.foreach(_.setVisible(!onlyLogin))
    logo.foreach(_.setVisible(!onlyLogin))

    emailField.foreach { field =>
      field.setValidator(emailValidator)
      field.setResource(R.layout.guided_edit_text_sign_in__email)
      field.setText(email.currentValue.getOrElse(""))
      field.getEditText.addTextListener(email ! _)
    }

    passwordField.foreach { field =>
      field.setValidator(passwordValidator)
      field.setResource(R.layout.guided_edit_text_sign_in__password)
      field.setText(password.currentValue.getOrElse(""))
      field.getEditText.addTextListener(password ! _)
    }

    nameField.foreach { field =>
      field.setValidator(nameValidator)
      field.setResource(R.layout.guided_edit_text_sign_in__name)
      field.setText(name.currentValue.getOrElse(""))
      field.getEditText.addTextListener(name ! _)
    }

    phoneField.foreach(field =>
      if (onlyLogin) field.setVisible(false)
      else {
        field.setText(phone.currentValue.getOrElse(""))
        field.addTextListener(phone ! _)
      }
    )

    termsOfService.foreach { text =>
      TextViewUtils.linkifyText(text, getColor(R.color.white), true, new Runnable {
        override def run(): Unit = browserController.openUrl(getString(R.string.url_terms_of_service_personal))
      })
    }
    countryButton.foreach(_.setOnClickListener(this))
    countryCodeText.foreach(_.setOnClickListener(this))
    confirmationButton.foreach(_.setOnClickListener(this))
    confirmationButton.foreach(_.setAccentColor(Color.WHITE))
    setConfirmationButtonActive(isValid.currentValue.getOrElse(false))
    forgotPasswordButton.foreach(_.setOnClickListener(this))
    companyLoginButton.foreach(_.setOnClickListener(this))
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View =
    returning(inflater.inflate(R.layout.sign_in_fragment, container, false)) { view =>
      findById[TabIndicatorLayout](view, R.id.til__app_entry).setLabels(Array[Int](R.string.new_reg__phone_signup__create_account, R.string.i_have_an_account))
      container.setBackgroundColor(Color.TRANSPARENT)
    }

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    phoneButton.foreach(_.setOnClickListener(this))
    emailButton.foreach(_.setOnClickListener(this))
    closeButton.foreach(_.setOnClickListener(this))
    tabSelector.foreach { tabSelector =>
      tabSelector.setLabels(Array[Int](R.string.new_reg__phone_signup__create_account, R.string.i_have_an_account))
      tabSelector.setTextColor(ContextCompat.getColorStateList(getContext, R.color.wire__text_color_dark_selector))
      tabSelector.setSelected(TabPages.SIGN_IN)

      tabSelector.setCallback(new Callback {
        override def onItemSelected(pos: Int): Unit = {
          pos match  {
            case TabPages.CREATE_ACCOUNT =>
              tabSelector.setSelected(TabPages.CREATE_ACCOUNT)
              uiSignInState.mutate(_ => SignInMethod(Register, Phone))
            case TabPages.SIGN_IN =>
              tabSelector.setSelected(TabPages.SIGN_IN)
              uiSignInState.mutate {
                case SignInMethod(Register, _, _) => SignInMethod(Login, Email)
                case other => other
              }
            case _ =>
          }
        }
      })
    }

    uiSignInState.head.map {
      case SignInMethod(Login, _, _) => tabSelector.foreach(_.setSelected(TabPages.SIGN_IN))
      case SignInMethod(Register, _, false) => tabSelector.foreach(_.setSelected(TabPages.CREATE_ACCOUNT))
      case _ =>
    } (Threading.Ui)
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)

    val transition = if (SDK_INT >= KITKAT) Option(new AutoTransition2()) else None

    def switchScene(sceneIndex: Int): Unit = transition.fold[Unit]({
      container.foreach(_.removeAllViews())
      container.foreach(c => LayoutInflater.from(getContext).inflate(scenes(sceneIndex), c))
    })(tr => container.foreach(c => TransitionManager.go(Scene.getSceneForLayout(c, scenes(sceneIndex), getContext), tr)))

    uiSignInState.onUi { state =>
      state match {
        case SignInMethod(Login, Email, onlyLogin) =>
          switchScene(0)
          setupViews(onlyLogin)
          setEmailButtonSelected()
          emailField.foreach(_.getEditText.requestFocus())
        case SignInMethod(Login, Phone, false) =>
          switchScene(1)
          setupViews(onlyLogin = false)
          setPhoneButtonSelected()
          phoneField.foreach(_.requestFocus())
        case SignInMethod(Register, Email, false) =>
          switchScene(2)
          setupViews(onlyLogin = false)
          setEmailButtonSelected()
          nameField.foreach(_.getEditText.requestFocus())
        case SignInMethod(Register, Phone, false) =>
          switchScene(3)
          setupViews(onlyLogin = false)
          setPhoneButtonSelected()
          phoneField.foreach(_.requestFocus())
      }
      phoneCountry.currentValue.foreach(onCountryHasChanged)
    }

    uiSignInState.map(s => SignInMethod(s.signType, Phone)).onUi { method =>
      ZMessaging.globalModule.map(_.trackingService.track(SignUpScreenEvent(method)))(Threading.Ui)
    }

    isValid.onUi(setConfirmationButtonActive)
    phoneCountry.onUi(onCountryHasChanged)
    isAddingAccount.onUi(isAdding => closeButton.foreach(_.setVisible(isAdding)))
  }

  private def setConfirmationButtonActive(active: Boolean): Unit = {
    import PhoneConfirmationButton.State._
    confirmationButton.foreach(_.setState(if (active) CONFIRM else NONE))
  }

  private def setPhoneButtonSelected(): Unit = {
    phoneButton.foreach { phoneButton =>
      phoneButton.setBackground(getDrawable(R.drawable.selector__reg__signin))
      phoneButton.setTextColor(getColor(R.color.white))
    }
    emailButton.foreach { emailButton =>
      emailButton.setBackground(null)
      emailButton.setTextColor(getColor(R.color.white_40))
    }
  }

  private def setEmailButtonSelected(): Unit = {
    emailButton.foreach { emailButton =>
      emailButton.setBackground(getDrawable(R.drawable.selector__reg__signin))
      emailButton.setTextColor(getColor(R.color.white))
    }
    phoneButton.foreach { phoneButton =>
      phoneButton.setBackground(null)
      phoneButton.setTextColor(getColor(R.color.white_40))
    }
  }


  override def onResume() = {
    super.onResume()
    countryController.addObserver(this)
  }


  override def onPause() = {
    super.onPause()
    countryController.removeObserver(this)
  }

  override def onClick(v: View) = {
    v.getId match {
      case R.id.ttv_signin_sso =>
        extractTokenAndShowSSODialog(showIfNoToken = true)

      case R.id.ttv__new_reg__sign_in__go_to__email =>
        uiSignInState.mutate {
          case SignInMethod(x, Phone, _) => SignInMethod(x, Email)
          case other => other
        }

      case R.id.ttv__new_reg__sign_in__go_to__phone =>
        uiSignInState.mutate {
          case SignInMethod(x, Email, false) => SignInMethod(x, Phone)
          case other => other
        }

      case R.id.ll__signup__country_code__button | R.id.tv__country_code =>
        activity.openCountryBox()

      case R.id.pcb__signin__email => //TODO rename!
        implicit val ec = Threading.Ui
        KeyboardUtils.closeKeyboardIfShown(getActivity)
        activity.enableProgress(true)

        def onResponse[A](req: Either[ErrorResponse, A], method: SignInMethod) = {
          tracking.onEnteredCredentials(req, method)
          activity.enableProgress(false)
          req match {
            case Left(error) =>
              showErrorDialog(if (method.inputType == Email) EmailError(error) else PhoneError(error))
              Left({})
            case Right(res) => Right(res)
          }
        }

        uiSignInState.head.flatMap {
          case m@SignInMethod(Login, Email, _) =>
            for {
              email     <- email.head
              password  <- password.head
              req       <- accountsService.loginEmail(email, password)
            } yield onResponse(req, m).right.foreach { id =>
              activity.showFragment(FirstLaunchAfterLoginFragment(id), FirstLaunchAfterLoginFragment.Tag)
            }
          case m@SignInMethod(Register, Email, false) =>
            for {
              email     <- email.head
              password  <- password.head
              name      <- name.head
              req       <- accountsService.requestEmailCode(EmailAddress(email))
            } yield onResponse(req, m).right.foreach { _ =>
              activity.showFragment(VerifyEmailWithCodeFragment(email, name, password), VerifyEmailWithCodeFragment.Tag)
            }
          case m@SignInMethod(method, Phone, false) =>
            val isLogin = method == Login
            activity.enableProgress(true)
            for {
              country <- phoneCountry.head
              phoneStr <- phone.head
              phone = PhoneNumber(s"+${country.getCountryCode}$phoneStr")
              req <- accountsService.requestPhoneCode(phone, login = isLogin)
            } yield onResponse(req, m).right.foreach { _ =>
              activity.showFragment(VerifyPhoneFragment(phone.str, login = isLogin), VerifyPhoneFragment.Tag)
            }
          case SignInMethod(_, _, true) =>
            error(s"Invalid sign in state")
            Future.successful({})
          case _ => throw new NotImplementedError("Only login with email works right now") //TODO
        }

      case R.id.ttv_signin_forgot_password =>
        browserController.openForgotPasswordPage()
      case R.id.close_button =>
        activity.abortAddAccount()
      case _ =>
    }
  }

  override def onCountryHasChanged(country: Country): Unit = {
    phoneCountry ! country
    countryCodeText.foreach(_.setText(String.format("+%s", country.getCountryCode)) )
    countryNameText.foreach(_.setText(country.getName))
  }

  def clearCredentials(): Unit =
    Set(email, password, name, phone).foreach(_ ! "")

  override def onBackPressed(): Boolean =
    if (getFragmentManager.getBackStackEntryCount > 1) {
      getFragmentManager.popBackStack()
      true
    } else {
      false
    }

  override protected def onSSOConfirm(code: String): Unit = activity.showFragment(SSOWebViewFragment.newInstance(code.toString), SSOWebViewFragment.Tag)

  def activity = getActivity.asInstanceOf[AppEntryActivity]
}

object SignInFragment {

  val SignTypeArg = "SIGN_IN_TYPE"
  val InputTypeArg = "INPUT_TYPE"
  val OnlyLoginArg = "ONLY_LOGIN"

  def apply() = new SignInFragment

  def apply(signInMethod: SignInMethod): SignInFragment = {
    returning(new SignInFragment()) {
      _.setArguments(returning(new Bundle) { b =>
          b.putString(SignTypeArg, signInMethod.signType.str)
          b.putString(InputTypeArg, signInMethod.inputType.str)
          b.putBoolean(OnlyLoginArg, signInMethod.onlyLogin)
      })
    }
  }

  val Tag = logTagFor[SignInFragment]

  sealed trait SignType{
    val str: String
  }
  object Login extends SignType { override val str = "Login" }
  object Register extends SignType { override val str = "Register" }

  sealed trait InputType {
    val str: String
  }
  object Email extends InputType { override val str = "Email" }
  object Phone extends InputType { override val str = "Phone" }

  case class SignInMethod(signType: SignType, inputType: InputType = Email, onlyLogin: Boolean = false)
  val SignInOnlyLogin = SignInMethod(Login, Email, onlyLogin = true)
}

class AutoTransition2 extends TransitionSet {
  setOrdering(TransitionSet.ORDERING_TOGETHER)
  addTransition(new Fade(Fade.OUT)).addTransition(new ChangeBounds).addTransition(new Fade(Fade.IN))
}
