package se.kth.somabits.frontend

import azadev.kotlin.css.*
import azadev.kotlin.css.dimens.percent

val css = Stylesheet {
//    ".interfaces" {
//        display = "flex"
//        flexDirection = "row"
//    }
    ".sensor" and ".actuator" {
        display = "flex"
        flexFlow = "row wrap"
        alignContent = "space-between"
        width = "100%"
        padding = "4px"
        border = "solid black"
    }
    ".sensor".h5 {
        flexGrow = 5
    }
    ".sensor".details {
        width = "100%"
    }
    ".sensor".details.summary.span {
        width = "100%"
        textAlign = "center"
    }
    ".sensor".canvas {
        width = "100%"
    }
    ".actuator".h5 {
        flexGrow = 5
    }
    ".actuator" {
        div.c("controller-wrapper") {
            display = "flex"
            width = 100.percent
            justifyContent = "space-evenly"
            alignItems = "center"

            div.c("input-wrapper") {
                display = "flex"
                flexGrow = 1
                flexDirection = "column"

                input {
                    width = 100.percent
                }

                span {
                    alignSelf = "center"
                }
            }
        }

    }
}
